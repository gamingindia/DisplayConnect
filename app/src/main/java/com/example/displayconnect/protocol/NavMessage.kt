package com.example.displayconnect.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NavMessage(
    val speedKmh: Int = 0,
    val eta: String = "",
    val timeStr: String = "",
    val dateStr: String = "",
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val instruction: String,
    val distanceM: Int,
    val street: String,
    val routeScreenPoints: List<Pair<Int, Int>>,
    /** Part of the route already driven (ordered from the rider backwards), drawn grey by the display. */
    val pastScreenPoints: List<Pair<Int, Int>> = emptyList(),
    val streetSegments: List<IntArray> = emptyList(),
    val userScreenPoint: Pair<Int, Int>?,
    val html: String? = null,
    val remainingDistanceM: Int? = null,
    val remainingDurationS: Int? = null,
    val offRoute: Boolean = false,
    val language: String = "pt-BR",
    val gpsWeak: Boolean = false
) {
    fun toJson(): String {
        val route = JSONArray()
        routeScreenPoints.forEach { (x, y) ->
            route.put(JSONArray().put(x).put(y))
        }
        val json = JSONObject()
            .put("type", "nav")
            .put("spd", speedKmh)
            .put("lat", lat)
            .put("lon", lon)
            .put("bearing", bearing.toDouble())
            .put("instruction", java.text.Normalizer.normalize(instruction, java.text.Normalizer.Form.NFC).take(63))
            .put("distance_m", distanceM)
            .put("remaining_m", remainingDistanceM ?: -1)
            .put("remaining_s", remainingDurationS ?: -1)
            .put("off_route", offRoute)
            .put("lang", language)
            .put("gps_weak", gpsWeak)
            .put("street", java.text.Normalizer.normalize(street, java.text.Normalizer.Form.NFC).take(47))
            .put("route", route)
        if (pastScreenPoints.size >= 2) {
            val past = JSONArray()
            pastScreenPoints.forEach { (x, y) -> past.put(JSONArray().put(x).put(y)) }
            json.put("past", past)
        }
        if (eta.isNotEmpty()) json.put("eta", eta)
        if (timeStr.isNotEmpty()) json.put("time", timeStr)
        if (dateStr.isNotEmpty()) json.put("date", dateStr)

        if (streetSegments.isNotEmpty()) {
            val streets = JSONArray()
            streetSegments.forEach { seg ->
                if (seg.size >= 4) {
                    streets.put(JSONArray().put(seg[0]).put(seg[1]).put(seg[2]).put(seg[3]))
                }
            }
            if (streets.length() > 0) {
                json.put("streets", streets)
            }
        }
        userScreenPoint?.let { (x, y) ->
            json.put("user_x", x).put("user_y", y)
        }
        if (!html.isNullOrBlank()) {
            json.put("html", html.take(480))
        }
        return json.toString()
    }

    companion object {
        fun heartbeat(): String {
            val now = Date()
            val time = SimpleDateFormat("hh:mm a", Locale.US).format(now)
            val date = SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(now)
            return JSONObject()
                .put("type", "heartbeat")
                .put("time", time)
                .put("date", date)
                .toString()
        }

        fun loading(): String = """{"type":"loading"}"""
    }
}
