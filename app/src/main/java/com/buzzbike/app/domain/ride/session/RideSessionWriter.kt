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
 */
interface RideSessionWriter {
    suspend fun startRide(): String

    suspend fun stopRide(rideId: String)

    suspend fun saveRideDescriptors(
        rideId: String,
        descriptors: RideDescriptors,
        elevationGainM: Double = 0.0,
        elevationLossM: Double = 0.0,
        caloriesKcal: Double? = null,
    )
}
