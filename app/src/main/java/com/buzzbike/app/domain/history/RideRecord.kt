package com.buzzbike.app.domain.history

import com.buzzbike.app.domain.calories.CalorieSummary

/**
 * Domain projection of a completed ride for the history list and detail screen.
 *
 * This is the read model the UI consumes: the UI never sees Room entities.
 * [calories] is `null` when the persisted band is missing or inconsistent, so a
 * ride without a trustworthy estimate keeps no estimate at all rather than a
 * fabricated number (ADR-001, AGENTS.md §6.4).
 */
data class RideRecord(
    val id: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long?,
    val distanceM: Double,
    val movingTimeSeconds: Long,
    val stoppedTimeSeconds: Long,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val calories: CalorieSummary?,
)
