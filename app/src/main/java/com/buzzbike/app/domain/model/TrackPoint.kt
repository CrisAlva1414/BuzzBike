package com.buzzbike.app.domain.model

/**
 * One accepted position fix plus the optional signals attached to it.
 *
 * Pure domain model: no `android.*` imports. The `sensor/` adapter builds it
 * from `android.location.Location` and the inertial sensors.
 *
 * Optional sensor readings are represented with `null` instead of `NaN` as a
 * sentinel so that "not available" cannot be mistaken for a measurement.
 * [timestampMonotonicNanos] is the only ordering key (see ADR-005 §1).
 */
data class TrackPoint(
    val timestampMonotonicNanos: Long,
    val lat: Double,
    val lon: Double,
    val altitudeEllipsoidM: Double? = null,
    val altitudeMslM: Double? = null,
    val accuracyH: Double,
    val accuracyV: Double? = null,
    val speedMps: Double? = null,
    val speedAccuracyMps: Double? = null,
    val pressureHpa: Double? = null,
    val accelNormStd: Double? = null,
)
