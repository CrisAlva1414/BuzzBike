package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.calories.CalorieSummary
import com.buzzbike.app.domain.elevation.ElevationDescriptors
import com.buzzbike.app.domain.model.RideDescriptors

/**
 * Sealed contract of the ride session lifecycle.
 *
 * [Idle] is the transient value before [RideSessionManager.restore] resolves
 * whether there is a persisted ride to recover.
 *
 * Calories are always a product band ([CalorieSummary]), never a single exact
 * number (ADR-001, AGENTS.md §6.4). [LiveMetrics.speedMps] is the instantaneous
 * speed of the last usable fix, not an average. [Recording.autoPaused] follows
 * the moving/stopped hysteresis from ADR-005 §4, computed by
 * `MovingTimeEstimator` (no separate threshold here).
 */
sealed interface RideSessionState {
    /** Transient: the session has not been restored yet. */
    data object Idle : RideSessionState

    /** No active ride; shows the current GPS readiness and barometer presence. */
    data class Ready(
        val gps: GpsStatus,
        val hasBarometer: Boolean,
    ) : RideSessionState

    /** Active ride collecting points. [autoPaused] derives from ADR-005 §4. */
    data class Recording(
        val rideId: String,
        val live: LiveMetrics,
        val autoPaused: Boolean,
    ) : RideSessionState

    /** User-paused ride; accumulated points are kept. */
    data class Paused(
        val rideId: String,
        val live: LiveMetrics,
    ) : RideSessionState

    /** Ride stopped and persisted; waiting for the user to acknowledge. */
    data class Finished(
        val rideId: String,
        val summary: RideSummary,
    ) : RideSessionState

    /** Persisted active ride found after a process death or app restart. */
    data class Recoverable(
        val rideId: String,
    ) : RideSessionState
}

/** GPS readiness exposed while no ride is recording. */
enum class GpsStatus { NO_PERMISSION, SEARCHING, FIX }

/** Platform-neutral WGS84 coordinate for the live trace. */
data class LatLon(
    val lat: Double,
    val lon: Double,
)

/**
 * Real-time projection of the active ride.
 *
 * [calories] reuses [CalorieSummary] so the band keeps a single source of truth
 * (`CalorieResult.toSummary()`, ADR-001). [tailPoints] is capped by the session
 * layer to keep rendering cheap.
 */
data class LiveMetrics(
    /** Instantaneous speed of the last usable fix, in m/s. Never an average. */
    val speedMps: Double,
    val distanceM: Double,
    val movingSeconds: Long,
    val elevationGainM: Double,
    val altitudeM: Double?,
    /** Product band from ADR-001; `null` until the engine has a usable estimate. */
    val calories: CalorieSummary?,
    val hasProfile: Boolean,
    /** Last N points (N = 200) used to draw the live trace. */
    val tailPoints: List<LatLon>,
) {
    companion object {
        val empty: LiveMetrics =
            LiveMetrics(
                speedMps = 0.0,
                distanceM = 0.0,
                movingSeconds = 0L,
                elevationGainM = 0.0,
                altitudeM = null,
                calories = null,
                hasProfile = false,
                tailPoints = emptyList(),
            )
    }
}

/**
 * Final aggregate of a completed ride.
 *
 * [calories] is the persisted product band (ADR-001) and [hasProfile] records
 * whether the estimate used a user profile or the fallback assumptions.
 */
data class RideSummary(
    val descriptors: RideDescriptors,
    val elevation: ElevationDescriptors,
    val calories: CalorieSummary?,
    val hasProfile: Boolean,
)
