package com.buzzbike.app.domain.sync

/**
 * Aggregated, data-minimized payload for sending one ride to Hermes.
 *
 * ADR-003 requires that the raw high-frequency GPS track never leaves the
 * device by default. The payload carries only aggregate metrics plus
 * decimated [waypoints] produced by [RidePayloadBuilder]; those waypoints are
 * an approximation for visualization, not the original trace.
 *
 * Pure domain model: no `android.*` imports and no serialization annotations.
 * [version] lets the receiver evolve independently. Version 2 replaced the
 * scalar `caloriesKcal` field with the [CaloriesPayload] object so the receiver
 * always gets the estimate together with its uncertainty band and model
 * (ADR-001, ADR-003 update 2026-10-28).
 */
data class RideSyncPayload(
    val version: Int = 2,
    val rideId: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long?,
    val distanceM: Double,
    val movingTimeSeconds: Long,
    val stoppedTimeSeconds: Long,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val calories: CaloriesPayload?,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val waypoints: List<Waypoint>,
)

/**
 * Calorie estimate as a product band (ADR-001).
 *
 * The receiver must not treat [estimateKcal] as exact: [lowKcal] and
 * [highKcal] carry the uncertainty band and [model] identifies which cascade
 * level produced it. The payload deliberately omits calories entirely when any
 * of these four values is missing.
 */
data class CaloriesPayload(
    val estimateKcal: Double,
    val lowKcal: Double,
    val highKcal: Double,
    val model: String,
)

/**
 * One decimated point of the ride.
 *
 * [timestampMillis] is reconstructed relative to the ride start, because
 * [com.buzzbike.app.domain.model.TrackPoint] only stores monotonic time.
 * [elevationM] is null when neither altitude source was usable.
 */
data class Waypoint(
    val timestampMillis: Long,
    val lat: Double,
    val lon: Double,
    val elevationM: Double?,
)
