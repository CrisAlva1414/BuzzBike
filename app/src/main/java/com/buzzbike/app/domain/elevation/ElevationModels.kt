package com.buzzbike.app.domain.elevation

/**
 * Elevation sample from sensors.
 * All altitudes in meters. Pressure in hPa.
 * Timestamps are monotonic nanoseconds.
 */
data class ElevationSample(
    val timestampNanos: Long,
    val pressureHpa: Double? = null,
    val altitudeGpsMslM: Double? = null,
    val altitudeGpsEllipsoidM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val speedMps: Double? = null,
    val accelMagnitudeMps2: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

/**
 * Source of elevation data used for gain/loss calculation.
 */
enum class ElevationSource {
    BAROMETER,
    GPS_MSL,
    GPS_ELLIPSOID,
    DEM_ANCHOR,
    NONE,
}

/**
 * Elevation descriptors for a ride segment.
 */
data class ElevationDescriptors(
    val gainM: Double,
    val lossM: Double,
    val netM: Double,
    val source: ElevationSource,
    val confidence: Double, // 0.0 to 1.0
    val hasBarometer: Boolean,
    val anchorSource: ElevationSource,
    val gradePercent: Double? = null,
    val vamMph: Double? = null, // meters per hour
    val uncertaintyM: Double = 0.0,
)
