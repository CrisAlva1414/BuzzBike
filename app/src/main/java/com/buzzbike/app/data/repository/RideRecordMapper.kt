package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.domain.calories.CalorieSummary
import com.buzzbike.app.domain.history.RideRecord

/**
 * Maps a persisted ride into the domain history read model.
 *
 * Calories are only projected when the whole persisted band is present,
 * finite and ordered (`low <= high`). Anything else yields `null`; the mapper
 * never fills gaps with invented values (ADR-001).
 */
internal fun RideEntity.toRideRecord(): RideRecord =
    RideRecord(
        id = id,
        startTimeMillis = startTimeMillis,
        endTimeMillis = endTimeMillis,
        distanceM = distanceM,
        movingTimeSeconds = movingTimeSeconds,
        stoppedTimeSeconds = stoppedTimeSeconds,
        elevationGainM = elevationGainM,
        elevationLossM = elevationLossM,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        calories = toCalorieSummary(),
    )

private fun RideEntity.toCalorieSummary(): CalorieSummary? {
    val estimate = caloriesKcal
    val low = caloriesLowKcal
    val high = caloriesHighKcal
    val model = calorieModel
    return when {
        estimate == null || low == null -> null
        high == null || model == null -> null
        else -> bandSummary(estimate, low, high, model)
    }
}

private fun bandSummary(
    estimate: Double,
    low: Double,
    high: Double,
    model: String,
): CalorieSummary? {
    val finite = estimate.isFinite() && low.isFinite() && high.isFinite()
    return if (finite && low <= high) {
        CalorieSummary(estimateKcal = estimate, lowKcal = low, highKcal = high, model = model)
    } else {
        null
    }
}
