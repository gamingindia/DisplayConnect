package com.example.displayconnect.routing

import com.example.displayconnect.offline.GeoBounds
import com.example.displayconnect.offline.StreetSource
import com.example.displayconnect.offline.StreetWay
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

/**
 * High-speed vector-tile street provider backed by OpenFreeMap CDN (.pbf / MVT).
 * Keeps the OverpassStreetProvider class name so existing callers require zero changes.
 */
class OverpassStreetProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) : StreetSource {

    @Volatile
    private var cachedTileTemplate: String? = null

    override suspend fun fetch(bounds: List<GeoBounds>): List<StreetWay> = withContext(Dispatchers.IO) {
        require(bounds.isNotEmpty())
        val template = resolveTileTemplate()
        val zoom = 14

        // Map all requested GeoBounds into unique z=14 slippy-map (x, y) vector tiles
        val tileCoords = linkedSetOf<Pair<Int, Int>>()
        for (b in bounds) {
            val xMin = lonToTileX(b.west, zoom)
            val xMax = lonToTileX(b.east, zoom)
            val yMin = latToTileY(b.north, zoom)
            val yMax = latToTileY(b.south, zoom)
            for (x in minOf(xMin, xMax)..maxOf(xMin, xMax)) {
                for (y in minOf(yMin, yMax)..maxOf(yMin, yMax)) {
                    tileCoords.add(x to y)
                }
            }
        }

        // Fetch and decode all needed .pbf vector tiles concurrently
        val waysById = linkedMapOf<Long, StreetWay>()
        val deferreds = tileCoords.map { (tx, ty) ->
            async {
                currentCoroutineContext().ensureActive()
                val url = template
                    .replace("{z}", zoom.toString())
                    .replace("{x}", tx.toString())
                    .replace("{y}", ty.toString())
                val bytes = fetchBytes(url)
                if (bytes.isNotEmpty()) {
                    MvtRoadDecoder.decodeTransportationWays(bytes, zoom, tx, ty)
                } else {
                    emptyList()
                }
            }
        }

        for (tileWays in deferreds.awaitAll()) {
            for (way in tileWays) {
                waysById.putIfAbsent(way.id, way)
            }
        }

        waysById.values.toList()
    }

    private suspend fun resolveTileTemplate(): String {
        cachedTileTemplate?.let { return it }
        val request = Request.Builder()
            .url("https://tiles.openfreemap.org/planet")
            .header("User-Agent", "DisplayConnect-CYD/2.1 (personal route offline map)")
            .get()
            .build()
        val raw = fetchBytes(request)
        val json = JSONObject(String(raw, StandardCharsets.UTF_8))
        val tiles = json.getJSONArray("tiles")
        check(tiles.length() > 0) { "OpenFreeMap TileJSON has no tile endpoints" }
        val resolved = tiles.getString(0)
        cachedTileTemplate = resolved
        return resolved
    }

    private suspend fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DisplayConnect-CYD/2.1 (personal route offline map)")
            .get()
            .build()
        return fetchBytes(request)
    }

    private suspend fun fetchBytes(request: Request): ByteArray = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val data = response.use { res ->
                        if (res.code == 204 || res.code == 404) return@use ByteArray(0)
                        check(res.isSuccessful) { "Map CDN error (HTTP ${res.code})" }
                        val raw = res.body?.bytes() ?: ByteArray(0)
                        // Automatically decompress if server sent raw gzip bytes
                        if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                        } else {
                            raw
                        }
                    }
                    if (cont.isActive) cont.resume(data)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        })
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        val n = 1 shl zoom
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    /**
     * Zero-dependency Mapbox Vector Tile (MVT v2.1) protobuf wire decoder.
     * Extracts LineString features from the "transportation" layer and converts local tile
     * cursor coordinates into WGS84 LatLon StreetWay polylines.
     */
private object MvtRoadDecoder {

        fun decodeTransportationWays(pbf: ByteArray, zoom: Int, tileX: Int, tileY: Int): List<StreetWay> {
            val reader = ProtoReader(pbf, 0, pbf.size)
            val result = ArrayList<StreetWay>(256)
            var syntheticIdBase = ((zoom.toLong() and 0x1FL) shl 48) or
                ((tileX.toLong() and 0xFFFFFFL) shl 24) or
                (tileY.toLong() and 0xFFFFFFL)

            while (!reader.eof()) {
                val tag = reader.readVarint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x07L).toInt()
                if (field == 3 && wire == 2) {
                    val len = reader.readVarint().toInt()
                    val end = reader.pos + len
                    parseLayer(pbf, reader.pos, end, zoom, tileX, tileY, syntheticIdBase, result)
                    syntheticIdBase += 100_000L
                    reader.pos = end
                } else {
                    reader.skipField(wire)
                }
            }
            return result
        }

        private fun parseLayer(
            buf: ByteArray,
            start: Int,
            end: Int,
            zoom: Int,
            tileX: Int,
            tileY: Int,
            idSeed: Long,
            out: MutableList<StreetWay>
        ) {
            val r = ProtoReader(buf, start, end)
            var layerName = ""
            var extent = 4096
            val featureRanges = ArrayList<Long>()

            while (!r.eof()) {
                val tag = r.readVarint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x07L).toInt()
                when {
                    field == 1 && wire == 2 -> {
                        val len = r.readVarint().toInt()
                        layerName = String(buf, r.pos, len, StandardCharsets.UTF_8)
                        r.pos += len
                    }
                    field == 2 && wire == 2 -> {
                        val len = r.readVarint().toInt()
                        val fStart = r.pos
                        val fEnd = fStart + len
                        featureRanges.add((fStart.toLong() shl 32) or (fEnd.toLong() and 0xFFFFFFFFL))
                        r.pos = fEnd
                    }
                    field == 5 && wire == 0 -> {
                        extent = r.readVarint().toInt().coerceAtLeast(1)
                    }
                    else -> r.skipField(wire)
                }
            }

            if (layerName != "transportation") return

            for ((idx, range) in featureRanges.withIndex()) {
                val fStart = (range ushr 32).toInt()
                val fEnd = range.toInt()
                parseFeature(buf, fStart, fEnd, extent, zoom, tileX, tileY, idSeed + idx, out)
            }
        }

        private fun parseFeature(
            buf: ByteArray,
            start: Int,
            end: Int,
            extent: Int,
            zoom: Int,
            tileX: Int,
            tileY: Int,
            fallbackId: Long,
            out: MutableList<StreetWay>
        ) {
            val r = ProtoReader(buf, start, end)
            var featureId = fallbackId
            var geomType = 0
            var geomStart = -1
            var geomEnd = -1

            while (!r.eof()) {
                val tag = r.readVarint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x07L).toInt()
                when {
                    field == 1 && wire == 0 -> {
                        val rawId = r.readVarint()
                        if (rawId != 0L) featureId = rawId
                    }
                    field == 3 && wire == 0 -> {
                        geomType = r.readVarint().toInt()
                    }
                    field == 4 && wire == 2 -> {
                        val len = r.readVarint().toInt()
                        geomStart = r.pos
                        geomEnd = geomStart + len
                        r.pos = geomEnd
                    }
                    else -> r.skipField(wire)
                }
            }

            // GeomType 2 == LINESTRING
            if (geomType != 2 || geomStart < 0) return

            val gr = ProtoReader(buf, geomStart, geomEnd)
            var cx = 0L
            var cy = 0L
            var lineIndex = 0
            var currentLine = ArrayList<LatLon>(16)

            while (!gr.eof()) {
                val cmdInt = gr.readVarint()
                val cmd = (cmdInt and 0x7L).toInt()
                val count = (cmdInt ushr 3).toInt()
                when (cmd) {
                    1 -> { // MoveTo
                        for (i in 0 until count) {
                            if (currentLine.size >= 2) {
                                out.add(StreetWay((featureId shl 4) + lineIndex++, currentLine))
                            }
                            currentLine = ArrayList(16)
                            cx += decodeZigZag(gr.readVarint())
                            cy += decodeZigZag(gr.readVarint())
                            currentLine.add(tilePixelToLatLon(cx.toDouble(), cy.toDouble(), extent, zoom, tileX, tileY))
                        }
                    }
                    2 -> { // LineTo
                        for (i in 0 until count) {
                            cx += decodeZigZag(gr.readVarint())
                            cy += decodeZigZag(gr.readVarint())
                            currentLine.add(tilePixelToLatLon(cx.toDouble(), cy.toDouble(), extent, zoom, tileX, tileY))
                        }
                    }
                    7 -> { // ClosePath
                        if (currentLine.isNotEmpty()) {
                            currentLine.add(currentLine.first())
                        }
                    }
                    else -> break
                }
            }

            if (currentLine.size >= 2) {
                out.add(StreetWay((featureId shl 4) + lineIndex, currentLine))
            }
        }

        private fun tilePixelToLatLon(
            px: Double,
            py: Double,
            extent: Int,
            zoom: Int,
            tileX: Int,
            tileY: Int
        ): LatLon {
            val n = (1 shl zoom).toDouble()
            val normX = tileX + (px / extent.toDouble())
            val normY = tileY + (py / extent.toDouble())
            val lon = (normX / n) * 360.0 - 180.0
            val latRad = atan(sinh(PI * (1.0 - 2.0 * normY / n)))
            val lat = Math.toDegrees(latRad)
            return LatLon(lat, lon)
        }

        private fun decodeZigZag(n: Long): Long = (n ushr 1) xor -(n and 1L)

        private class ProtoReader(
            private val buf: ByteArray,
            var pos: Int,
            private val limit: Int
        ) {
            fun eof(): Boolean = pos >= limit

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (pos < limit) {
                    val b = buf[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) return result
                    shift += 7
                    if (shift >= 64) break
                }
                return result
            }

            fun skipField(wireType: Int) {
                when (wireType) {
                    0 -> readVarint()
                    1 -> pos += 8
                    2 -> pos += readVarint().toInt()
                    5 -> pos += 4
                    else -> pos = limit
                }
            }
        }
    }
