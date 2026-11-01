package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint

/**
 * Domain port for the ride session lifecycle and its final aggregate.
 *
 * The data layer implements it (Room) so [RideSessionManager] never depends on
 * `data` or `android.*` (AGENTS.md §3).
 *
 * [RideDescriptors] does not carry elevation or calories, so they travel as
 * explicit parameters. They default to neutral values to keep callers that only
 * persist the aggregate source-compatible.
 *
 * Calories are persisted as the full product band (`estimate` +
 * `lowKcal`/`highKcal` + `model`) required by ADR-001 and AGENTS.md §6.4. A
 * null `calorieModel` means "no reliable calorie data"; consumers must not
 * reconstruct a number from the estimate alone.
 */
interface RideSessionWriter {
    /**
     * Creates the persisted active ride and stores [profile] as the snapshot
     * the ride will use for its whole lifetime. `null` leaves the four snapshot
     * columns empty (ride recorded without a profile).
     */
    suspend fun startRide(profile: UserProfile? = null): String

    /** Returns the id of the persisted active ride, if any, for recovery. */
    suspend fun findActiveRideId(): String?

    /**
     * Reads the profile snapshot stored when [rideId] was started.
     *
     * Recovery must use this value instead of the current profile: the user may
     * have edited or cleared the profile if the process died, and historical
     * rides keep the inputs they were actually calculated with (ADR-001 §5.3.4,
     * ADR-007 §3).
     */
    suspend fun persistedProfile(rideId: String): UserProfile?

    /**
     * Loads the persisted points of [rideId] in ascending timestamp order.
     *
     * Used by recovery to rebuild the aggregate after the in-memory trace was
     * lost to a process death; it must not substitute a local recomputation.
     */
    suspend fun loadTrackPoints(rideId: String): List<TrackPoint>

    suspend fun stopRide(rideId: String)

    /** Deletes a ride and its track points (discard / orphan cleanup). */
    suspend fun deleteRide(rideId: String)

    @Suppress("LongParameterList")
    suspend fun saveRideDescriptors(
        rideId: String,
        descriptors: RideDescriptors,
        elevationGainM: Double = 0.0,
        elevationLossM: Double = 0.0,
        caloriesKcal: Double? = null,
        caloriesLowKcal: Double? = null,
        caloriesHighKcal: Double? = null,
        calorieModel: String? = null,
        profile: UserProfile? = null,
    )
}
