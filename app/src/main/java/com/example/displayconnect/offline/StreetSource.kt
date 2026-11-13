package com.example.displayconnect.offline

import com.example.displayconnect.routing.*
import kotlinx.coroutines.*
import java.io.File

class OfflineMapRepository(
    root: File,
    private val source: StreetSource,
    private val pauseMs: Long = 1800
) {
    private val disk = OfflineDiskStore(root)

    suspend fun findRoute(origin: LatLon, destination: LatLon, profile: RouteProfile): RouteData? =
        withContext(Dispatchers.IO) { disk.findRoute(origin, destination, profile) }

    suspend fun saveRoute(route: RouteData, destination: LatLon, profile: RouteProfile) =
        withContext(Dispatchers.IO) { disk.saveRoute(route, destination, profile) }

    suspend fun prepare(route: RouteData, scale: Double, onProgress: suspend (OfflineMapState) -> Unit) =
        withContext(Dispatchers.IO) {
            var state = OfflineMapState(downloading = true, radiusM = RouteCoverage.radiusMeters(scale).toInt())
            onProgress(state)
            try {
                val tiles = RouteCoverage.plan(route.coordinates, scale)
                val missing = tiles.filter { currentCoroutineContext().ensureActive(); disk.load(it) == null }
                state = state.copy(total = tiles.size, downloaded = tiles.size - missing.size)
                onProgress(state)

                // Larger batches (12 tiles/request) reduce Overpass API calls by 3x and prevent 429 rate-limits
                for ((index, batch) in missing.chunked(12).withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (index > 0) delay(pauseMs)
                    val ways = fetchWithRetry(batch.map { it.bounds() })
                    currentCoroutineContext().ensureActive()
                    for (tile in batch) {
                        disk.save(tile, ways.filter { it.bounds.intersects(tile.bounds()) })
                        state = state.copy(downloaded = state.downloaded + 1)
                        onProgress(state)
                    }
                }
                state = state.copy(downloading = false, error = null)
                onProgress(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onProgress(
                    state.copy(
                        downloading = false,
                        error = e.message ?: "Offline map download failed. Check internet and tap retry."
                    )
                )
            }
        }

    private suspend fun fetchWithRetry(boundsList: List<GeoBounds>): List<StreetWay> {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            currentCoroutineContext().ensureActive()
            try {
                return source.fetch(boundsList)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 3) {
                    delay(2500L * attempt)
                }
            }
        }
        throw lastError ?: IllegalStateException("Overpass API timeout")
    }

    /** No network here. Only read saved tiles needed to cover this complete viewport. */
    suspend fun visibleWays(center: LatLon, scale: Double): Pair<List<List<LatLon>>, Boolean> =
        withContext(Dispatchers.IO) {
            val tiles = RouteCoverage.tiles(RouteCoverage.around(center, RouteCoverage.radiusMeters(scale)))
            val ways = linkedMapOf<Long, StreetWay>()
            var complete = true
            for (tile in tiles) {
                currentCoroutineContext().ensureActive()
                val cached = disk.load(tile)
                if (cached == null) complete = false else cached.forEach { ways[it.id] = it }
            }
            ways.values.map { it.points } to complete
        }
}
