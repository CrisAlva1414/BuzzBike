package com.buzzbike.app.domain.sync

import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint

/**
 * Input to [RidePayloadBuilder], independent of the `data` and `android` layers.
 *
 * [points] is the full cleaned trace for the ride. The builder filters invalid
 * coordinates, orders by monotonic time and decimates them before they reach
 * [RideSyncPayload], preserving ADR-003's data-minimization rule.
 */
data class RideSyncInput(
    val rideId: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long?,
    val descriptors: RideDescriptors,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val caloriesKcal: Double?,
    val caloriesLowKcal: Double?,
    val caloriesHighKcal: Double?,
    val calorieModel: String?,
    val maxSpeedMps: Double,
    val points: List<TrackPoint>,
)
