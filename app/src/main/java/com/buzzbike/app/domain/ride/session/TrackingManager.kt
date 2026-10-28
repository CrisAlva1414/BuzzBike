package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.model.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates a ride tracking session: gates on the location permission,
 * subscribes to [LocationSensorPort], converts readings to [TrackPoint] and
 * persists them through [TrackPointWriter].
 *
 * The class has no Android dependencies: the sensor and permission check are
 * injected as ports, so the whole flow is testable on the JVM.
 *
 * @param scope scope used to collect the sensor flow. Callers that own the
 *   manager lifecycle should pass their own scope; the default exists for
 *   simple use and is never cancelled automatically.
 */
class TrackingManager(
    private val locationSensor: LocationSensorPort,
    private val trackPointWriter: TrackPointWriter,
    private val permissionChecker: LocationPermissionChecker,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _trackPoints = MutableStateFlow<List<TrackPoint>>(emptyList())

    /** Points accumulated during the current/last session, in arrival order. */
    val trackPoints: Flow<List<TrackPoint>> = _trackPoints.asStateFlow()

    private var trackingJob: Job? = null

    /**
     * Starts collecting location readings for [rideId].
     *
     * Returns `false` (and does not subscribe) when the fine location
     * permission is missing or [rideId] is blank. A second call while already
     * tracking is a no-op that returns `true`.
     */
    @Synchronized
    fun startTracking(rideId: String): Boolean {
        if (rideId.isBlank() || !permissionChecker.hasFineLocationPermission()) {
            return false
        }

        startCollectionIfIdle(rideId)
        return true
    }

    /** Stops the sensor subscription. Accumulated points are kept. */
    @Synchronized
    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
    }

    private fun startCollectionIfIdle(rideId: String) {
        if (trackingJob?.isActive == true) return

        _trackPoints.value = emptyList()
        trackingJob =
            scope.launch {
                locationSensor.locationUpdates().collect { reading ->
                    val point = reading.toTrackPointOrNull()
                    if (point != null) {
                        trackPointWriter.saveTrackPoint(rideId, point)
                        _trackPoints.value = _trackPoints.value + point
                    }
                }
            }
    }

    private fun LocationReading.hasValidCoordinates(): Boolean {
        val coordinatesAreFinite = lat.isFinite() && lon.isFinite()
        val coordinatesAreInRange = lat in LAT_RANGE && lon in LON_RANGE
        return coordinatesAreFinite && coordinatesAreInRange
    }

    private fun LocationReading.toTrackPointOrNull(): TrackPoint? =
        if (hasValidCoordinates()) {
            TrackPoint(
                timestampMonotonicNanos = timestampNanos,
                lat = lat,
                lon = lon,
                altitudeEllipsoidM = altitudeEllipsoidM,
                accuracyH = accuracyH,
                accuracyV = accuracyV,
                speedMps = speedMps,
                bearing = bearing,
            )
        } else {
            null
        }

    private companion object {
        private val LAT_RANGE = -90.0..90.0
        private val LON_RANGE = -180.0..180.0
    }
}
