package com.example.displayconnect.map

import com.example.displayconnect.routing.LatLon
import kotlin.math.*

object StreetContextProjector {
    // Must match NAV_MAX_STREET_SEGMENTS in the CYD firmware.
    const val MAX_SEGMENTS = 128

    fun projectSegments(
        centerLat: Double, centerLon: Double, ways: List<List<LatLon>>,
        scaleMeters: Double, maxSegments: Int = MAX_SEGMENTS
    ): List<IntArray> {
        val budget = maxSegments.coerceIn(0, MAX_SEGMENTS)
        if (budget == 0) return emptyList()
        val visible = ways.mapNotNull { way ->
            ScreenGeometry.visibleRuns(way.map {
                MapProjector.projectUnclipped(centerLat, centerLon, it.lat, it.lon, scaleMeters)
            }).takeIf { it.isNotEmpty() }
        }
        // First simplify all visible streets equally, keeping distant roads as well as central roads.
        var simplified = visible.map { runs -> runs.map { ScreenGeometry.simplify(it) } }
        for (tolerance in listOf(2.0, 3.0)) {
            if (simplified.sumOf { runs -> runs.sumOf { it.size - 1 } } <= budget) break
            simplified = visible.map { runs -> runs.map { ScreenGeometry.simplify(it, tolerance) } }
        }
        // In very dense areas, distribute complete ways over a 6x3 screen grid.
        val groups = simplified.groupBy { runs ->
            val points = runs.flatten()
            val x = (points.minOf { it.x } + points.maxOf { it.x }) / 2
            val y = (points.minOf { it.y } + points.maxOf { it.y }) / 2
            (x / (MapProjector.MAP_WIDTH / 6.0)).toInt().coerceIn(0, 5) +
                6 * (y / (MapProjector.MAP_HEIGHT / 3.0)).toInt().coerceIn(0, 2)
        }.toSortedMap().values.map { java.util.ArrayDeque(it) }
        val result = mutableListOf<IntArray>()
        // Vector tiles carry a buffer, so roads near a tile border arrive from both neighbours. Never spend the
        // 128-segment budget twice on the same pixels.
        val seen = HashSet<Long>()
        fun key(a: Int, b: Int, c: Int, d: Int): Long {
            val p = (a.toLong() shl 48) or (b.toLong() shl 32) or (c.toLong() shl 16) or d.toLong()
            return p
        }
        while (groups.any { it.isNotEmpty() }) {
            for (group in groups) {
                if (group.isEmpty()) continue
                val runs = group.removeFirst()
                val fresh = ArrayList<IntArray>()
                for (run in runs) for ((a, b) in run.zipWithNext()) {
                    val s = intArrayOf(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt())
                    if (s[0] == s[2] && s[1] == s[3]) continue
                    val k1 = key(s[0], s[1], s[2], s[3]); val k2 = key(s[2], s[3], s[0], s[1])
                    if (k1 in seen || k2 in seen) continue
                    fresh.add(s)
                }
                if (fresh.isEmpty() || fresh.size > budget - result.size) continue
                for (s in fresh) { seen.add(key(s[0], s[1], s[2], s[3])); result.add(s) }
                if (result.size == budget) return result
            }
        }
        return result
    }
}
