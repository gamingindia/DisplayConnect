package com.example.displayconnect.routing

import com.example.displayconnect.offline.StreetWay
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Dependency-free Mapbox Vector Tile (MVT 2.x) decoder that extracts road LineStrings from the
 * OpenMapTiles "transportation" layer and converts them to WGS84 [StreetWay] polylines.
 *
 * Wire-format reminders (these are the places the previous version went wrong):
 *  - A length-delimited field (wire type 2) is  <len varint><len bytes>. Skipping it MUST consume
 *    BOTH the varint and the payload.
 *  - Layer:   name=1, features=2, keys=3, values=4, extent=5, version=15
 *  - Feature: id=1, tags=2 (packed), type=3 (2 == LINESTRING), geometry=4 (packed)
 */
object MvtRoadDecoder {

    /** Road classes that only clutter a tiny 240x232 motorcycle display. */
    private val IGNORED_CLASSES = setOf("rail", "transit", "ferry", "aerialway", "path")

    data class Result(
        /** Road polylines with globally unique, tile-scoped ids. */
        val ways: List<StreetWay>,
        /** Names of all layers found in the tile (diagnostics). */
        val layers: List<String>,
        /** Number of LineString features in "transportation" before class filtering. */
        val lineFeatures: Int
    )

    /** @throws IllegalArgumentException when the bytes are not a well-formed vector tile. */
    fun decode(pbf: ByteArray, zoom: Int, tileX: Int, tileY: Int): Result {
        val ways = ArrayList<StreetWay>(256)
        val layers = ArrayList<String>()
        val ctx = Ctx(zoom, tileX, tileY)
        val tile = Reader(pbf, 0, pbf.size)
        while (!tile.eof()) {
            val tag = tile.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 3 && wire == 2) {
                val len = tile.length()
                val end = tile.pos + len
                layers += decodeLayer(pbf, tile.pos, end, ctx, ways)
                tile.pos = end
            } else {
                tile.skip(wire)
            }
        }
        return Result(ways, layers, ctx.lineFeatures)
    }

    fun decodeTransportationWays(pbf: ByteArray, zoom: Int, tileX: Int, tileY: Int): List<StreetWay> =
        decode(pbf, zoom, tileX, tileY).ways

    // ---------------------------------------------------------------------------------------------

    private class Ctx(val zoom: Int, val tileX: Int, val tileY: Int) {
        var counter = 0L
        var lineFeatures = 0
        /** tileX(14 bits) | tileY(14 bits) | running counter(30 bits): unique across all tiles. */
        fun nextId(): Long =
            (tileX.toLong() shl 44) or (tileY.toLong() shl 30) or (counter++ and 0x3FFFFFFFL)
    }

    private fun decodeLayer(buf: ByteArray, start: Int, end: Int, ctx: Ctx, out: MutableList<StreetWay>): String {
        val r = Reader(buf, start, end)
        var name = ""
        var extent = 4096
        val features = ArrayList<IntArray>()      // [start, end]
        val keys = ArrayList<String>()
        val values = ArrayList<String>()

        while (!r.eof()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 2 -> { val l = r.length(); name = r.string(l) }
                field == 2 && wire == 2 -> { val l = r.length(); features += intArrayOf(r.pos, r.pos + l); r.pos += l }
                field == 3 && wire == 2 -> { val l = r.length(); keys += r.string(l) }
                field == 4 && wire == 2 -> { val l = r.length(); values += decodeValue(buf, r.pos, r.pos + l); r.pos += l }
                field == 5 && wire == 0 -> extent = r.varint().toInt().coerceAtLeast(1)
                else -> r.skip(wire)
            }
        }
        if (name != "transportation") return name

        for (f in features) decodeFeature(buf, f[0], f[1], extent, keys, values, ctx, out)
        return name
    }

    private fun decodeValue(buf: ByteArray, start: Int, end: Int): String {
        val r = Reader(buf, start, end)
        var text = ""
        while (!r.eof()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 1 && wire == 2) { val l = r.length(); text = r.string(l) } else r.skip(wire)
        }
        return text
    }

    private fun decodeFeature(
        buf: ByteArray, start: Int, end: Int, extent: Int,
        keys: List<String>, values: List<String>, ctx: Ctx, out: MutableList<StreetWay>
    ) {
        val r = Reader(buf, start, end)
        var geomType = 0
        var tagsStart = -1; var tagsEnd = -1
        var geomStart = -1; var geomEnd = -1
        while (!r.eof()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 2 && wire == 2 -> { val l = r.length(); tagsStart = r.pos; tagsEnd = r.pos + l; r.pos += l }
                field == 3 && wire == 0 -> geomType = r.varint().toInt()
                field == 4 && wire == 2 -> { val l = r.length(); geomStart = r.pos; geomEnd = r.pos + l; r.pos += l }
                else -> r.skip(wire)   // includes the feature id (field 1); we never use OSM ids
            }
        }
        if (geomType != 2 || geomStart < 0) return          // 2 == LINESTRING
        ctx.lineFeatures++

        if (tagsStart >= 0) {
            val t = Reader(buf, tagsStart, tagsEnd)
            while (!t.eof()) {
                val k = t.varint().toInt()
                if (t.eof()) break
                val v = t.varint().toInt()
                if (keys.getOrNull(k) == "class" && values.getOrNull(v) in IGNORED_CLASSES) return
            }
        }

        val g = Reader(buf, geomStart, geomEnd)
        var cx = 0L
        var cy = 0L
        var line = ArrayList<LatLon>(16)
        fun flush() { if (line.size >= 2) out += StreetWay(ctx.nextId(), line); line = ArrayList(16) }

        while (!g.eof()) {
            val cmdInt = g.varint()
            val cmd = (cmdInt and 7L).toInt()
            val count = (cmdInt ushr 3).toInt()
            when (cmd) {
                1 -> repeat(count) {                         // MoveTo: starts a new part
                    flush()
                    cx += zigzag(g.varint()); cy += zigzag(g.varint())
                    line.add(toLatLon(cx, cy, extent, ctx))
                }
                2 -> repeat(count) {                         // LineTo
                    cx += zigzag(g.varint()); cy += zigzag(g.varint())
                    line.add(toLatLon(cx, cy, extent, ctx))
                }
                7 -> Unit                                    // ClosePath (no parameters)
                else -> throw IllegalArgumentException("Bad MVT geometry command $cmd")
            }
        }
        flush()
    }

    private fun toLatLon(px: Long, py: Long, extent: Int, ctx: Ctx): LatLon {
        val n = (1L shl ctx.zoom).toDouble()
        val x = ctx.tileX + px.toDouble() / extent
        val y = ctx.tileY + py.toDouble() / extent
        val lon = x / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / n))))
        return LatLon(lat, lon)
    }

    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1L)

    private class Reader(private val buf: ByteArray, var pos: Int, private val limit: Int) {
        init { require(pos in 0..limit && limit <= buf.size) { "Bad MVT range" } }

        fun eof() = pos >= limit

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < limit) { "Truncated MVT varint" }
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "MVT varint too long" }
            }
        }

        /** Reads a length prefix and checks that the payload fits. Does NOT consume the payload. */
        fun length(): Int {
            val len = varint()
            require(len in 0..(limit - pos).toLong()) { "MVT length out of range" }
            return len.toInt()
        }

        fun string(len: Int): String {
            val s = String(buf, pos, len, StandardCharsets.UTF_8)
            pos += len
            return s
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> { val l = length(); pos += l }          // <-- the original `pos += readVarint()` lost the length byte
                5 -> advance(4)
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $wire")
            }
        }

        private fun advance(n: Int) { require(pos + n <= limit) { "Truncated MVT field" }; pos += n }
    }
}
