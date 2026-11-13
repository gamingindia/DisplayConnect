package com.example.displayconnect.routing

import android.util.Log
import com.example.displayconnect.offline.GeoBounds
import com.example.displayconnect.offline.StreetSource
import com.example.displayconnect.offline.StreetWay
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

/**
 * Street provider backed by OpenFreeMap vector tiles (.pbf / MVT, z14).
 * The class name is kept so existing callers need no changes. Decoding lives in [MvtRoadDecoder].
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
    private val gate = Semaphore(4)

    override suspend fun fetch(bounds: List<GeoBounds>): List<StreetWay> = withContext(Dispatchers.IO) {
        require(bounds.isNotEmpty())
        val template = resolveTileTemplate()
        val zoom = ZOOM

        val tileCoords = linkedSetOf<Pair<Int, Int>>()
        for (b in bounds) {
            // The max edges are nudged inward so a bound that ends exactly on a tile edge does not pull in the next tile.
            val xMin = lonToTileX(b.west, zoom)
            val xMax = lonToTileX(max(b.west, b.east - EDGE_EPS), zoom)
            val yMin = latToTileY(b.north, zoom)
            val yMax = latToTileY(min(b.north, b.south + EDGE_EPS), zoom)
            for (x in xMin..max(xMin, xMax)) for (y in yMin..max(yMin, yMax)) tileCoords.add(x to y)
        }

        val perTile = tileCoords.map { (tx, ty) ->
            async {
                gate.withPermit {
                    currentCoroutineContext().ensureActive()
                    val url = template.replace("{z}", "$zoom").replace("{x}", "$tx").replace("{y}", "$ty")
                    val bytes = fetchBytes(url)
                    if (bytes.isEmpty()) emptyList<StreetWay>()        // 204/404: a valid, empty tile
                    else {
                        val decoded = try {
                            MvtRoadDecoder.decode(bytes, zoom, tx, ty)
                        } catch (e: IllegalArgumentException) {
                            throw IOException("Malformed vector tile $zoom/$tx/$ty: ${e.message}", e)
                        }
                        if (decoded.layers.isEmpty()) throw IOException("Vector tile $zoom/$tx/$ty has no layers")
                        Log.d(TAG, "tile $zoom/$tx/$ty bytes=${bytes.size} layers=${decoded.layers} " +
                            "lineFeatures=${decoded.lineFeatures} ways=${decoded.ways.size}")
                        decoded.ways
                    }
                }
            }
        }.awaitAll()

        // Ids are tile-scoped and unique, so nothing is de-duplicated across tiles: a road that crosses
        // a tile border legitimately appears once per tile and each tile needs its own part.
        val all = perTile.flatten()
        Log.d(TAG, "OpenFreeMap tiles=${tileCoords.size} decodedWays=${all.size}")
        all
    }

    private suspend fun resolveTileTemplate(): String {
        cachedTileTemplate?.let { return it }
        val request = Request.Builder()
            .url("https://tiles.openfreemap.org/planet")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val raw = fetchBytes(request)
        val tiles = JSONObject(String(raw, StandardCharsets.UTF_8)).getJSONArray("tiles")
        check(tiles.length() > 0) { "OpenFreeMap TileJSON has no tile endpoints" }
        return tiles.getString(0).also { cachedTileTemplate = it }
    }

    private suspend fun fetchBytes(url: String): ByteArray =
        fetchBytes(Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build())

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
                        // OkHttp already inflates Content-Encoding: gzip; this covers raw gzip bodies.
                        if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                        } else raw
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
        val rad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val n = 1 shl zoom
        return floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    private companion object {
        const val ZOOM = 14
        const val EDGE_EPS = 1e-9
        const val TAG = "DisplayConnectMap"
        const val USER_AGENT = "DisplayConnect-CYD/2.1 (personal route offline map)"
    }
}
