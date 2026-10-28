package com.buzzbike.app.domain.model

/**
 * Aggregated descriptors computed for a ride.
 *
 * Pure domain model: no `android.*` imports. The time split keeps
 * [elapsedSeconds] and [movingSeconds] separate on purpose; see ADR-005 §6.
 * [distanceIsLowerBound] flags that the distance is a lower bound because the
 * trace had gaps or rejected points.
 */
data class RideDescriptors(
    val distanceM: Double,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val stoppedSeconds: Long,
    val avgMovingSpeedMps: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val stopCount: Int,
    val stopDurationsSeconds: List<Long>,
    val acceptedPoints: Int,
    val rejectedPoints: Int,
    val gapCount: Int,
    val distanceIsLowerBound: Boolean,
    val modelVersion: String,
)
