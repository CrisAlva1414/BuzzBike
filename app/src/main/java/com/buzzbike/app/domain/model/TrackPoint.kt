package com.buzzbike.app.domain.model

/**
 * Immutable track point for domain processing.
 * All coordinates in WGS84. Timestamps are monotonic nanoseconds.
 * Nullable fields indicate missing/invalid data (no NaN sentinels).
 */
data class TrackPoint(
    val timestampMonotonicNanos: Long,
    val lat: Double,
    val lon: Double,
    val altitudeMslM: Double? = null,
    val altitudeEllipsoidM: Double? = null,
    val accuracyH: Double? = null,
    val accuracyV: Double? = null,
    val speedMps: Double? = null,
    val bearing: Float? = null,
    val pressureHpa: Double? = null,
)
