package com.buzzbike.app.domain.sync

/**
 * Port that loads the persisted data needed to build a ride sync payload.
 *
 * Implementations live in `data/`: `domain/` must stay free of Room,
 * Android and JSON types. Returning `null` means the ride does not exist
 * (or was deleted), which the coordinator reports as [RideSyncOutcome.NoData].
 */
interface RideSyncDataSource {
    suspend fun load(rideId: String): RideSyncInput?
}
