package com.buzzbike.app.domain.history

import com.buzzbike.app.domain.model.TrackPoint

/**
 * Full read model of a completed ride: its descriptors plus every stored
 * track point.
 *
 * The detail screen and the GPX exporter both consume this object, so the UI
 * never reaches into Room for the trace. Points are in the DAO's stable order
 * (ascending monotonic timestamp).
 */
data class RideDetail(
    val ride: RideRecord,
    val points: List<TrackPoint>,
)
