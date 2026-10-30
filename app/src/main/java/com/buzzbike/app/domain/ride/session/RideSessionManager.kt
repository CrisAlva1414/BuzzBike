package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.calories.toSummary
import com.buzzbike.app.domain.elevation.ElevationDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.MovingState
import com.buzzbike.app.domain.ride.MovingTimeEstimator
import com.buzzbike.app.domain.ride.SpeedResolver
import com.buzzbike.app.domain.ride.engine.RideEngine
import com.buzzbike.app.domain.ride.engine.RideEngineResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAIL_POINTS_LIMIT = 200

/**
 * Coordinates the lifecycle of a ride session: starts/stops persistence and
 * tracking, consumes [TrackingManager] flows, feeds the points to [RideEngine]
 * and exposes the evolving [RideSessionState].
 *
 * Pure domain class: it depends only on domain types and the
 * [RideSessionWriter] port. Android/data wiring belongs to the caller.
 *
 * Auto-pause ([RideSessionState.Recording.autoPaused]) comes from
 * [MovingTimeEstimator] and its ADR-005 §4 hysteresis; this class does not add
 * any new threshold. Calories are always published as the [CalorieSummary]
 * band produced by `CalorieResult.toSummary()` (ADR-001, AGENTS.md §6.4).
 *
 * @param permissionChecker runtime permission port. It has no default on
 *   purpose: callers must pass the real check instead of assuming permission.
 * @param sensorAvailability optional sensor report; defaults to "no barometer"
 *   until the Android adapter is wired in a later slice.
 * @param scope scope used to collect the tracking flows. Callers that own the
 *   manager lifecycle should pass their own scope; the default exists for
 *   simple use and is never cancelled automatically.
 */
@Suppress("TooManyFunctions")
class RideSessionManager(
    private val rideEngine: RideEngine,
    private val trackingManager: TrackingManager,
    private val rideSessionWriter: RideSessionWriter,
    private val permissionChecker: LocationPermissionChecker,
    private val sensorAvailability: SensorAvailability = NoBarometerSensorAvailability,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<RideSessionState>(RideSessionState.Idle)

    /** Current session state, safe to collect from the UI layer. */
    val state: StateFlow<RideSessionState> = _state.asStateFlow()

    private val stateLock = Any()
    private val lifecycleMutex = Mutex()
    private val collectorJobs = mutableListOf<Job>()

    @Volatile
    private var accumulatedPoints: List<TrackPoint> = emptyList()

    @Volatile
    private var latestRideResult: RideEngineResult? = null

    @Volatile
    private var latestElevation: ElevationDescriptors? = null

    @Volatile
    private var latestMovingState: MovingState? = null

    /**
     * Resolves the session after a cold start: a persisted active ride becomes
     * [RideSessionState.Recoverable]; otherwise the manager is
     * [RideSessionState.Ready] with the current permission/GPS status.
     *
     * Only acts from [RideSessionState.Idle]. The ViewModel is recreated on
     * every configuration change, so calling this over a live session must not
     * overwrite it: any other state is a no-op.
     */
    suspend fun restore() {
        lifecycleMutex.withLock {
            if (_state.value is RideSessionState.Idle) {
                val activeRideId = rideSessionWriter.findActiveRideId()
                _state.value =
                    if (activeRideId != null) {
                        RideSessionState.Recoverable(activeRideId)
                    } else {
                        readyState(currentGpsStatus())
                    }
            }
        }
    }

    /**
     * Salvages a ride left [RideSessionState.Recoverable] after a process death.
     *
     * Loads the points persisted in `track_points`, runs them through
     * [RideEngine] (same path as a normal stop, including elevation and the
     * ADR-001 calorie band), persists the descriptors, marks the row as
     * finished and publishes [RideSessionState.Finished]. Returns the salvaged
     * ride id, or `null` when the current state is not recoverable.
     *
     * An interrupted ride with no persisted points has nothing worth keeping:
     * instead of fabricating an empty [RideSessionState.Finished], the row is
     * deleted and the manager returns to [RideSessionState.Ready].
     */
    suspend fun salvageRecoverableRide(): String? =
        lifecycleMutex.withLock {
            when (val current = _state.value) {
                is RideSessionState.Recoverable -> salvageLocked(current.rideId)
                else -> null
            }
        }

    private suspend fun salvageLocked(rideId: String): String? {
        val points = rideSessionWriter.loadTrackPoints(rideId)
        return if (points.isEmpty()) {
            rideSessionWriter.deleteRide(rideId)
            resetAccumulated()
            _state.value = readyState(currentGpsStatus())
            null
        } else {
            finalizeRide(rideId, points)
            rideId
        }
    }

    /**
     * Starts a new persisted ride and subscriptions.
     *
     * Lifecycle transitions are serialized with [lifecycleMutex]. Starting is
     * only allowed from [RideSessionState.Idle], [RideSessionState.Ready] or
     * [RideSessionState.Finished]; from [RideSessionState.Recording],
     * [RideSessionState.Paused] or [RideSessionState.Recoverable] this is a
     * no-op that returns `null` without creating a row. That makes a second
     * (double-tap or concurrent) start harmless instead of leaving an orphan
     * active ride whose points could land under the wrong id.
     *
     * The fine-location guard runs **before** creating the row, so a missing
     * permission never leaves a phantom active ride. Returns the new id, or
     * `null` when the session could not start.
     */
    suspend fun startRide(): String? =
        lifecycleMutex.withLock {
            when {
                !canStartNewRide(_state.value) -> null
                !permissionChecker.hasFineLocationPermission() -> {
                    _state.value = readyState(GpsStatus.NO_PERMISSION)
                    null
                }
                else -> beginRide()
            }
        }

    /**
     * Pauses the active session without ending it: sensor collection stops but
     * the accumulated points are kept for [resumeRide].
     */
    suspend fun pauseRide() =
        lifecycleMutex.withLock {
            val current = _state.value as? RideSessionState.Recording
            if (current != null) {
                trackingManager.pauseTracking()
                trackingManager.stopElevationTracking()
                _state.value = RideSessionState.Paused(current.rideId, current.live)
            }
        }

    /**
     * Resumes the paused session. No-op when there is no paused ride.
     *
     * If [TrackingManager.resumeTracking] returns `false` (for example the
     * fine-location permission was revoked while paused) the state stays
     * [RideSessionState.Paused] with the same metrics and elevation is not
     * restarted. The UI surfaces the permission notice from that state.
     */
    suspend fun resumeRide() =
        lifecycleMutex.withLock {
            val current = _state.value as? RideSessionState.Paused
            if (current != null) {
                if (trackingManager.resumeTracking(current.rideId)) {
                    trackingManager.startElevationTracking(current.rideId)
                    _state.value =
                        RideSessionState.Recording(
                            rideId = current.rideId,
                            live = current.live,
                            autoPaused = false,
                        )
                } else {
                    _state.value = current
                }
            }
        }

    /**
     * Stops tracking, processes the whole ride and persists its final
     * descriptors (including the ADR-001 calorie band), then publishes
     * [RideSessionState.Finished].
     */
    suspend fun stopRide() =
        lifecycleMutex.withLock {
            val rideId = activeRideId()
            trackingManager.stopTracking()
            trackingManager.stopElevationTracking()
            cancelAndJoinCollectors()
            if (rideId != null) {
                finalizeRide(rideId)
            }
        }

    /**
     * Stops tracking and deletes the ride row, discarding the session.
     *
     * Works for an active, finished or recoverable ride and returns the manager
     * to [RideSessionState.Ready].
     */
    suspend fun discardRide() {
        lifecycleMutex.withLock {
            val rideId = discardableRideId()
            trackingManager.stopTracking()
            trackingManager.stopElevationTracking()
            // Wait for in-flight collectors before deleting, so no late point
            // can be written after the ride row is gone (orphan track points).
            cancelAndJoinCollectors()
            if (rideId != null) {
                rideSessionWriter.deleteRide(rideId)
            }
            resetAccumulated()
            _state.value = readyState(currentGpsStatus())
        }
    }

    /**
     * Acknowledges a [RideSessionState.Finished] ride from the UI.
     *
     * Persistence already happened in [stopRide]; this only clears the screen
     * state back to [RideSessionState.Ready].
     */
    fun acknowledgeFinished() {
        if (_state.value is RideSessionState.Finished) {
            refreshReady()
        }
    }

    /** Recomputes [RideSessionState.Ready] (return from settings, discard, …). */
    fun refreshReady() {
        _state.value = readyState(currentGpsStatus())
    }

    private suspend fun beginRide(): String? {
        cancelCollectors()
        val rideId = rideSessionWriter.startRide()
        resetAccumulated()

        if (!trackingManager.startTracking(rideId)) {
            trackingManager.stopTracking()
            trackingManager.stopElevationTracking()
            rideSessionWriter.deleteRide(rideId)
            _state.value = readyState(GpsStatus.NO_PERMISSION)
            return null
        }

        trackingManager.startElevationTracking(rideId)
        _state.value =
            RideSessionState.Recording(
                rideId = rideId,
                live = LiveMetrics.empty,
                autoPaused = false,
            )
        startCollectors()
        return rideId
    }

    private suspend fun finalizeRide(
        rideId: String,
        points: List<TrackPoint> = accumulatedPoints,
    ) {
        val (result, elevation) =
            synchronized(stateLock) {
                val finalResult = rideEngine.processRide(points)
                latestRideResult = finalResult
                finalResult to (latestElevation ?: finalResult.elevationDescriptors)
            }

        val summary = result.calorieResult?.toSummary()
        rideSessionWriter.stopRide(rideId)
        rideSessionWriter.saveRideDescriptors(
            rideId = rideId,
            descriptors = result.rideDescriptors,
            elevationGainM = elevation.gainM,
            elevationLossM = elevation.lossM,
            caloriesKcal = summary?.estimateKcal,
            caloriesLowKcal = summary?.lowKcal,
            caloriesHighKcal = summary?.highKcal,
            calorieModel = summary?.model,
        )
        _state.value =
            RideSessionState.Finished(
                rideId = rideId,
                summary =
                    RideSummary(
                        descriptors = result.rideDescriptors,
                        elevation = elevation,
                        calories = summary,
                        hasProfile = result.calorieResult?.hasProfile ?: false,
                    ),
            )
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

    /**
     * Cancels the collectors and waits for them to finish. Used by the
     * terminating transitions (stop/discard) so a point already in flight is
     * fully processed before the ride is finalized or deleted.
     */
    private suspend fun cancelAndJoinCollectors() {
        val jobs = collectorJobs.toList()
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        collectorJobs.removeAll(jobs)
    }

    private fun onTrackPoints(points: List<TrackPoint>) {
        val result = rideEngine.processRide(points)
        synchronized(stateLock) {
            accumulatedPoints = points
            latestRideResult = result
            latestMovingState = MovingTimeEstimator.estimate(points)
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
        val result = latestRideResult ?: return
        val live = buildLiveMetrics(result)
        when (val current = _state.value) {
            is RideSessionState.Recording ->
                _state.value =
                    current.copy(
                        live = live,
                        autoPaused = latestMovingState?.stoppedSinceNanos != null,
                    )
            is RideSessionState.Paused -> _state.value = current.copy(live = live)
            else -> Unit
        }
    }

    private fun buildLiveMetrics(result: RideEngineResult): LiveMetrics {
        val elevation = latestElevation ?: result.elevationDescriptors
        val calorieResult = result.calorieResult
        return LiveMetrics(
            speedMps = instantaneousSpeed(accumulatedPoints),
            distanceM = result.rideDescriptors.distanceM,
            movingSeconds = result.rideDescriptors.movingSeconds,
            elevationGainM = elevation.gainM,
            altitudeM = accumulatedPoints.asReversed().firstNotNullOfOrNull { it.altitudeMslM },
            calories = calorieResult?.toSummary(),
            hasProfile = calorieResult?.hasProfile ?: false,
            tailPoints =
                accumulatedPoints
                    .takeLast(TAIL_POINTS_LIMIT)
                    .map { LatLon(lat = it.lat, lon = it.lon) },
        )
    }

    private fun instantaneousSpeed(points: List<TrackPoint>): Double =
        points.asReversed().firstNotNullOfOrNull { SpeedResolver.resolve(it) } ?: 0.0

    private fun resetAccumulated() {
        synchronized(stateLock) {
            accumulatedPoints = emptyList()
            latestRideResult = null
            latestElevation = null
            latestMovingState = null
        }
    }

    private fun activeRideId(): String? =
        when (val current = _state.value) {
            is RideSessionState.Recording -> current.rideId
            is RideSessionState.Paused -> current.rideId
            else -> null
        }

    private fun discardableRideId(): String? =
        when (val current = _state.value) {
            is RideSessionState.Recording -> current.rideId
            is RideSessionState.Paused -> current.rideId
            is RideSessionState.Finished -> current.rideId
            is RideSessionState.Recoverable -> current.rideId
            else -> null
        }

    private fun canStartNewRide(current: RideSessionState): Boolean =
        when (current) {
            is RideSessionState.Idle, is RideSessionState.Ready, is RideSessionState.Finished -> true
            else -> false
        }

    private fun currentGpsStatus(): GpsStatus =
        if (permissionChecker.hasFineLocationPermission()) GpsStatus.SEARCHING else GpsStatus.NO_PERMISSION

    private fun readyState(gps: GpsStatus): RideSessionState =
        RideSessionState.Ready(gps = gps, hasBarometer = sensorAvailability.hasBarometer())
}
