package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.elevation.ElevationDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.engine.RideEngine
import com.buzzbike.app.domain.ride.engine.RideEngineResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real-time projection of the active ride session.
 *
 * [caloriesKcal] stays nullable on purpose: it is only present once the engine
 * produced a calorie result. The UI must still present it as an interval using
 * the engine's uncertainty model (ADR-001, AGENTS.md §6).
 */
data class RideSessionUiState(
    val isTracking: Boolean = false,
    val activeRideId: String? = null,
    val distanceM: Double = 0.0,
    val movingTimeSeconds: Long = 0L,
    val elevationGainM: Double = 0.0,
    val caloriesKcal: Double? = null,
    val avgSpeedMps: Double = 0.0,
)

/**
 * Coordinates the lifecycle of a ride session: starts/stops persistence and
 * tracking, consumes [TrackingManager] flows, feeds the points to [RideEngine]
 * and exposes the evolving aggregate through [uiState].
 *
 * Pure domain class: it depends only on domain types and the
 * [RideSessionWriter] port. Android/data wiring belongs to the caller.
 *
 * @param scope scope used to collect the tracking flows. Callers that own the
 *   manager lifecycle should pass their own scope; the default exists for
 *   simple use and is never cancelled automatically.
 */
class RideSessionManager(
    private val rideEngine: RideEngine,
    private val trackingManager: TrackingManager,
    private val rideSessionWriter: RideSessionWriter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _uiState = MutableStateFlow(RideSessionUiState())

    /** Current session state, safe to collect from the UI layer. */
    val uiState: Flow<RideSessionUiState> = _uiState.asStateFlow()

    private val stateLock = Any()
    private val collectorJobs = mutableListOf<Job>()

    @Volatile
    private var accumulatedPoints: List<TrackPoint> = emptyList()

    @Volatile
    private var latestRideResult: RideEngineResult? = null

    @Volatile
    private var latestElevation: ElevationDescriptors? = null

    /**
     * Starts a new persisted ride, subscribes to location and elevation
     * tracking and begins collecting their flows.
     *
     * @return the id assigned by [RideSessionWriter].
     */
    suspend fun startRide(): String {
        cancelCollectors()
        val rideId = rideSessionWriter.startRide()

        synchronized(stateLock) {
            accumulatedPoints = emptyList()
            latestRideResult = null
            latestElevation = null
        }

        val trackingStarted = trackingManager.startTracking(rideId)
        trackingManager.startElevationTracking(rideId)
        startCollectors()

        _uiState.value =
            RideSessionUiState(
                isTracking = trackingStarted,
                activeRideId = rideId,
            )
        return rideId
    }

    /**
     * Stops tracking, persists the final descriptors of the active ride and
     * clears [RideSessionUiState.activeRideId].
     *
     * A call with no active ride only clears the tracking flags.
     */
    suspend fun stopRide() {
        val rideId = _uiState.value.activeRideId
        trackingManager.stopTracking()
        trackingManager.stopElevationTracking()
        cancelCollectors()

        if (rideId != null) {
            val (result, elevation) =
                synchronized(stateLock) {
                    val finalResult = rideEngine.processRide(accumulatedPoints)
                    latestRideResult = finalResult
                    publishLocked()
                    finalResult to (latestElevation ?: finalResult.elevationDescriptors)
                }

            rideSessionWriter.stopRide(rideId)
            rideSessionWriter.saveRideDescriptors(
                rideId = rideId,
                descriptors = result.rideDescriptors,
                elevationGainM = elevation.gainM,
                elevationLossM = elevation.lossM,
                caloriesKcal = result.calorieResult?.totalKcal,
            )
        }

        _uiState.update { it.copy(isTracking = false, activeRideId = null) }
    }

    private fun startCollectors() {
        collectorJobs +=
            scope.launch {
                trackingManager.trackPoints.collect { points -> onTrackPoints(points) }
            }
        collectorJobs +=
            scope.launch {
                trackingManager.elevationDescriptors.collect { descriptors -> onElevation(descriptors) }
            }
    }

    private fun cancelCollectors() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()
    }

    private fun onTrackPoints(points: List<TrackPoint>) {
        val result = rideEngine.processRide(points)
        synchronized(stateLock) {
            accumulatedPoints = points
            latestRideResult = result
            publishLocked()
        }
    }

    private fun onElevation(descriptors: ElevationDescriptors) {
        synchronized(stateLock) {
            latestElevation = descriptors
            publishLocked()
        }
    }

    private fun publishLocked() {
        val result = latestRideResult
        val elevation = latestElevation ?: result?.elevationDescriptors
        _uiState.update { current ->
            current.copy(
                distanceM = result?.rideDescriptors?.distanceM ?: current.distanceM,
                movingTimeSeconds = result?.rideDescriptors?.movingSeconds ?: current.movingTimeSeconds,
                elevationGainM = elevation?.gainM ?: current.elevationGainM,
                caloriesKcal = result?.calorieResult?.totalKcal,
                avgSpeedMps = result?.rideDescriptors?.avgSpeedMps ?: current.avgSpeedMps,
            )
        }
    }
}
