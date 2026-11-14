package com.example.displayconnect.navigation

import com.example.displayconnect.map.MapProjector
import com.example.displayconnect.map.StreetContextProjector
import com.example.displayconnect.maps.MapsHtmlHolder
import com.example.displayconnect.models.AppSettings
import com.example.displayconnect.network.BleNavClient
import com.example.displayconnect.protocol.NavMessage
import com.example.displayconnect.routing.LatLon
import com.example.displayconnect.offline.OfflineMapRepository
import com.example.displayconnect.routing.OsrmRouteProvider
import com.example.displayconnect.routing.RouteData
import com.example.displayconnect.routing.RouteStep
import com.example.displayconnect.routing.RouteProgressCalculator
import com.example.displayconnect.utils.StatsTracker
import com.example.displayconnect.utils.TransmissionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.os.SystemClock
import android.util.Log
import com.example.displayconnect.utils.AppLanguage
import com.example.displayconnect.routing.NavigationText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NavigationEngine(
    private val scope: CoroutineScope,
    private val locationTracker: LocationTracker,
    private val routeProvider: OsrmRouteProvider,
    private val offlineMap: OfflineMapRepository,
    private val navClient: BleNavClient,
    private val settingsProvider: suspend () -> AppSettings,
    private val onNavigationEnded: () -> Unit = {}
) {

    @Volatile private var route: RouteData? = null
    @Volatile private var progressCalculator: RouteProgressCalculator? = null
    private var smoothedBearing: Float = Float.NaN
    private var destination: LatLon? = null
    private var locationJob: Job? = null
    private var downloadJob: Job? = null
    private var activeSettings: AppSettings? = null
    private var gpsFilter: GpsFilter? = null
    private var gpsWatchdog: Job? = null
    private var lastAcceptedMs = 0L
    @Volatile private var lastMessage: NavMessage? = null
    private val statsTracker = StatsTracker()

    // Off-route recalculation state
    @Volatile private var isRerouting = false
    private var lastRerouteMs = 0L

    fun startNavigation(destLat: Double, destLon: Double) {
        stopNavigation()
        MapsHtmlHolder.clear()
        TransmissionHub.clearNavigationError()
        destination = LatLon(destLat, destLon)
        TransmissionHub.setNavigating(true)
        locationJob = scope.launch {
            try {
                val settings = settingsProvider()
                activeSettings = settings
                gpsFilter = GpsFilter(when (settings.routeProfile) {
                    com.example.displayconnect.routing.RouteProfile.WALKING -> 8.0
                    com.example.displayconnect.routing.RouteProfile.BIKE -> 25.0
                    else -> 70.0
                })
                val intervalMs = 1000L / settings.navUpdateHz.coerceIn(1, 10)
                if (navClient.connectionState.value == com.example.displayconnect.models.ConnectionState.CONNECTED) {
                    navClient.sendNavMessage(NavMessage.loading())
                }
                val originUpdate = withTimeout(45_000) { locationTracker.locationFlow(intervalMs).first { acceptUpdate(it) } }
                val origin = LatLon(originUpdate.lat, originUpdate.lon)
                val dest = destination ?: return@launch
                val saved = offlineMap.findRoute(origin, dest, settings.routeProfile)
                val selected = saved ?: routeProvider.fetchRoute(origin, dest, settings.routeProfile).getOrElse {
                    error("Não foi possível calcular a rota. Confira a internet e o destino.")
                }
                offlineMap.saveRoute(selected, dest, settings.routeProfile)
                route = selected
                progressCalculator = RouteProgressCalculator(selected)
                retryOfflineDownload()
                gpsWatchdog = scope.launch {
                    while (true) {
                        delay(1500)
                        if (SystemClock.elapsedRealtime() - lastAcceptedMs > 5000) {
                            TransmissionHub.updateGps(TransmissionHub.gps.value.copy(weak = true))
                            sendWeakGpsFrame()
                        }
                    }
                }
                if (SystemClock.elapsedRealtime() - originUpdate.elapsedMs < 3000) publishUpdate(originUpdate, settings)
                locationTracker.locationFlow(intervalMs).conflate().collect {
                    if (acceptUpdate(it)) publishUpdate(it, settings) else if (TransmissionHub.gps.value.weak) sendWeakGpsFrame()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                downloadJob?.cancel()
                gpsWatchdog?.cancel()
                TransmissionHub.setNavigating(false)
                TransmissionHub.navigationFailed(if (AppLanguage.language.value == "en")
                    "No GPS fix. Move to an open area and try again." else "GPS sem posição. Vá para um local aberto e tente novamente.")
                onNavigationEnded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadJob?.cancel()
                gpsWatchdog?.cancel()
                TransmissionHub.setNavigating(false)
                TransmissionHub.navigationFailed(if (AppLanguage.language.value == "en")
                    "Unable to start navigation. Check precise location, internet and the destination." else
                    e.message ?: "Não foi possível iniciar a navegação.")
                onNavigationEnded()
            }
        }
    }

    fun retryOfflineDownload() {
        if (downloadJob?.isActive == true) return
        val currentRoute = route ?: return
        val settings = activeSettings ?: return
        val fixedScale = settings.mapScaleMeters.coerceIn(100.0, 380.0)
        downloadJob = scope.launch {
            offlineMap.prepare(currentRoute, fixedScale) { progress ->
                withContext(Dispatchers.Main) { TransmissionHub.updateOfflineMap(progress) }
            }
        }
    }

    fun stopNavigation() {
        locationJob?.cancel()
        locationJob = null
        downloadJob?.cancel()
        downloadJob = null
        gpsWatchdog?.cancel()
        gpsWatchdog = null
        gpsFilter = null
        lastMessage = null
        lastAcceptedMs = 0
        smoothedBearing = Float.NaN
        isRerouting = false
        lastRerouteMs = 0L
        route = null
        progressCalculator = null
        destination = null
        activeSettings = null
        TransmissionHub.setNavigating(false)
        statsTracker.reset()
    }

    private fun acceptUpdate(update: LocationUpdate): Boolean {
        val accepted = gpsFilter?.accept(GpsSample(LatLon(update.lat, update.lon), update.accuracyM,
            update.speedMps, update.bearing, update.elapsedMs), SystemClock.elapsedRealtime()) == true
        if (accepted) lastAcceptedMs = update.elapsedMs
        if (accepted || update.elapsedMs > lastAcceptedMs || SystemClock.elapsedRealtime() - lastAcceptedMs > 5000) {
            TransmissionHub.updateGps(GpsStatus(update.accuracyM.takeIf { it.isFinite() }, lastAcceptedMs, !accepted))
        }
        Log.d("DisplayConnectGPS", "accepted=$accepted accuracy=${update.accuracyM} ageMs=${SystemClock.elapsedRealtime() - update.elapsedMs}")
        return accepted
    }

    private fun sendWeakGpsFrame() {
        val previous = lastMessage ?: return
        if (previous.gpsWeak) return
        previous.copy(gpsWeak = true).also { lastMessage = it; navClient.sendNavMessage(it.toJson()) }
    }

    private suspend fun publishUpdate(raw: LocationUpdate, settings: AppSettings) = withContext(Dispatchers.Default) {
        val buildStarted = SystemClock.elapsedRealtime()
        val currentRoute = route ?: return@withContext
        val progress = progressCalculator?.update(LatLon(raw.lat, raw.lon))
            ?: com.example.displayconnect.routing.TripProgress()
        val position = GpsFilter.displayPosition(LatLon(raw.lat, raw.lon), progress.matchedPosition,
            progress.distanceFromRouteM, raw.accuracyM, raw.speedMps, raw.bearing, progress.routeBearing)
        val update = raw.copy(lat = position.lat, lon = position.lon)
        TransmissionHub.updateTripProgress(progress)

        // Automatic Off-Route Recalculation (with 6-second cooldown)
        if (progress.offRoute && !isRerouting && (SystemClock.elapsedRealtime() - lastRerouteMs) > 6000L) {
            val dest = destination
            if (dest != null) {
                isRerouting = true
                lastRerouteMs = SystemClock.elapsedRealtime()
                scope.launch(Dispatchers.IO) {
                    try {
                        val newOrigin = LatLon(raw.lat, raw.lon)
                        val rerouted = routeProvider.fetchRoute(newOrigin, dest, settings.routeProfile).getOrNull()
                        if (rerouted != null) {
                            route = rerouted
                            progressCalculator = RouteProgressCalculator(rerouted)
                            offlineMap.saveRoute(rerouted, dest, settings.routeProfile)
                            Log.d("DisplayConnectMap", "Off-route recalculation succeeded")
                        }
                    } catch (e: Exception) {
                        Log.w("DisplayConnectMap", "Off-route recalculation failed: ${e.message}")
                    } finally {
                        isRerouting = false
                    }
                }
            }
        }

        if (navClient.connectionState.value != com.example.displayconnect.models.ConnectionState.CONNECTED) {
            return@withContext
        }

        val step = findCurrentStep(update, currentRoute.steps)
        val distanceM = step?.let {
            haversineMeters(update.lat, update.lon, it.endLocation.lat, it.endLocation.lon).toInt()
        } ?: 0

        val speedKmh = if (raw.speedMps.isFinite() && raw.speedMps >= 0f) {
            (raw.speedMps * 3.6f).toInt()
        } else {
            0
        }

        // Fixed scale capped at 380m: eliminates zoom distortion at any speed
        val effectiveScaleMeters: Double = settings.mapScaleMeters.coerceIn(100.0, 380.0)

        val simplified = MapProjector.simplifyRoute(
            currentRoute.coordinates.map { it.lat to it.lon }
        )

        // Split the route at the rider: "ahead" is drawn blue, "past" grey (like Google Maps).
        val coords = currentRoute.coordinates
        val seg = progress.segmentIndex
        val matched = progress.matchedPosition
        val aheadPoints: List<Pair<Double, Double>>
        val pastPoints: List<Pair<Double, Double>>
        if (seg != null && matched != null && seg in 0 until coords.lastIndex) {
            val ahead = ArrayList<Pair<Double, Double>>(minOf(coords.size - seg, AHEAD_WINDOW + 1))
            ahead.add(matched.lat to matched.lon)
            for (k in (seg + 1)..minOf(coords.lastIndex, seg + AHEAD_WINDOW)) ahead.add(coords[k].lat to coords[k].lon)
            // Ordered from the rider backwards so the point budget keeps the part nearest to the rider.
            val past = ArrayList<Pair<Double, Double>>(minOf(seg + 2, PAST_WINDOW + 2))
            past.add(matched.lat to matched.lon)
            for (k in seg downTo maxOf(0, seg - PAST_WINDOW)) past.add(coords[k].lat to coords[k].lon)
            aheadPoints = ahead
            pastPoints = past
        } else {
            aheadPoints = coords.map { it.lat to it.lon }
            pastPoints = emptyList()
        }
        val screenRoute = MapProjector.projectRoute(
            centerLat = update.lat,
            centerLon = update.lon,
            routePoints = aheadPoints,
            scaleMeters = effectiveScaleMeters,
            maxPoints = 64
        )
        val screenPast = if (pastPoints.size >= 2) MapProjector.projectRoute(
            centerLat = update.lat,
            centerLon = update.lon,
            routePoints = pastPoints,
            scaleMeters = effectiveScaleMeters,
            maxPoints = 48
        ) else emptyList()
        val userPoint = MapProjector.projectPoint(
            centerLat = update.lat,
            centerLon = update.lon,
            lat = update.lat,
            lon = update.lon,
            scaleMeters = effectiveScaleMeters
        )

        val visibleWaysResult = offlineMap.visibleWays(
            LatLon(update.lat, update.lon),
            effectiveScaleMeters
        )
        val streets = visibleWaysResult.first
        val covered = visibleWaysResult.second
        TransmissionHub.updateMapCoverage(covered)
        val streetSegments = withContext(Dispatchers.Default) { StreetContextProjector.projectSegments(
            centerLat = update.lat,
            centerLon = update.lon,
            ways = streets,
            scaleMeters = effectiveScaleMeters
        ) }

        // Smooth circular bearing filter (eliminates 1 Hz GNSS heading snaps)
        val rawBearing = if (update.bearing.isFinite()) {
            update.bearing
        } else {
            computeBearingFromRoute(update, simplified)
        }
        smoothedBearing = if (!smoothedBearing.isFinite()) {
            rawBearing
        } else {
            val delta = ((rawBearing - smoothedBearing + 540f) % 360f) - 180f
            (smoothedBearing + delta * 0.35f + 360f) % 360f
        }
        val bearing = smoothedBearing

        val remSec = progress.remainingDurationS ?: 0
        val etaString = if (remSec > 0) {
            val etaMillis = System.currentTimeMillis() + (remSec * 1000L)
            SimpleDateFormat("hh:mm a", Locale.US).format(Date(etaMillis))
        } else {
            ""
        }

        val nowDate = Date()
        val currentTime = SimpleDateFormat("hh:mm a", Locale.US).format(nowDate)
        val currentDate = SimpleDateFormat("EEE, dd MMM", Locale.US).format(nowDate)

        val message = NavMessage(
            timeStr = currentTime,
            dateStr = currentDate,
            lat = update.lat,
            lon = update.lon,
            bearing = bearing,
            speedKmh = speedKmh,
            eta = etaString,
            instruction = NavigationText.instruction(step?.instruction ?: "Continue", AppLanguage.language.value),
            language = AppLanguage.language.value,
            distanceM = distanceM,
            remainingDistanceM = progress.remainingDistanceM,
            remainingDurationS = progress.remainingDurationS,
            offRoute = progress.offRoute,
            street = step?.street ?: "",
            routeScreenPoints = screenRoute,
            pastScreenPoints = screenPast,
            streetSegments = streetSegments,
            userScreenPoint = userPoint,
            html = MapsHtmlHolder.get()
        )

        val json = message.toJson()
        currentCoroutineContext().ensureActive()
        Log.d("DisplayConnectMap", "buildMs=${SystemClock.elapsedRealtime() - buildStarted} segments=${streetSegments.size} bytes=${json.toByteArray().size}")
        if (SystemClock.elapsedRealtime() - raw.elapsedMs > 3000) {
            Log.d("DisplayConnectMap", "Discarded stale map frame")
            return@withContext
        }
        lastMessage = message
        if (navClient.sendNavMessage(json)) {
            statsTracker.onNavUpdate(json.toByteArray().size)
            TransmissionHub.updateStats(
                statsTracker.currentStats(settings.resolutionLabel)
            )
        }
    }

    private companion object {
        const val AHEAD_WINDOW = 4000
        const val PAST_WINDOW = 800
    }

    private fun findCurrentStep(update: LocationUpdate, steps: List<RouteStep>): RouteStep? {
        if (steps.isEmpty()) return null
        var closest = steps.first()
        var minDist = Double.MAX_VALUE
        for (step in steps) {
            val d = haversineMeters(update.lat, update.lon, step.endLocation.lat, step.endLocation.lon)
            if (d < minDist) {
                minDist = d
                closest = step
            }
        }
        return closest
    }

    private fun computeBearingFromRoute(
        update: LocationUpdate,
        routePoints: List<Pair<Double, Double>>
    ): Float {
        if (routePoints.size < 2) return update.bearing.takeIf { it.isFinite() } ?: 0f
        var bestIndex = 0
        var minDist = Double.MAX_VALUE
        for (index in routePoints.indices) {
            val point = routePoints[index]
            val d = haversineMeters(
                update.lat,
                update.lon,
                point.first,
                point.second
            )
            if (d < minDist) {
                minDist = d
                bestIndex = index
            }
        }

        val nextIndex = (bestIndex + 1).coerceAtMost(routePoints.lastIndex)
        val firstPoint = routePoints[bestIndex]
        val nextPoint = routePoints[nextIndex]

        return bearingDegrees(
            firstPoint.first,
            firstPoint.second,
            nextPoint.first,
            nextPoint.second
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }
}
