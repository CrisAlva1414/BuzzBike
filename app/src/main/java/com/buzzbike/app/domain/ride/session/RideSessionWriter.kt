package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.model.RideDescriptors

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
    suspend fun startRide(): String

    suspend fun stopRide(rideId: String)

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
    )
}
