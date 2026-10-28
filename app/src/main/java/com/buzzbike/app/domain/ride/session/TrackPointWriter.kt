package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.model.TrackPoint

/**
 * Port over track-point persistence.
 *
 * The data layer provides the implementation; the tracking use case depends
 * only on this interface so `domain` never imports `data` (AGENTS.md §3).
 * The narrow surface also keeps JVM tests free of Room.
 */
interface TrackPointWriter {
    suspend fun saveTrackPoint(
        rideId: String,
        point: TrackPoint,
    )
}
