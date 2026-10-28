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
 * [version] lets the receiver evolve independently; it starts at 1.
 */
data class RideSyncPayload(
    val version: Int = 1,
    val rideId: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long?,
    val distanceM: Double,
    val movingTimeSeconds: Long,
    val stoppedTimeSeconds: Long,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val caloriesKcal: Double?,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val waypoints: List<Waypoint>,
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
