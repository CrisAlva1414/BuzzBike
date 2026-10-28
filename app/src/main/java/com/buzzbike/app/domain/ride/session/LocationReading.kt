package com.buzzbike.app.domain.ride.session

/**
 * Platform-neutral snapshot of a single GPS fix.
 *
 * This type lives in `domain` on purpose: the tracking use case must not
 * depend on `android.location.Location` to stay JVM-testable. The sensor
 * adapter (`sensor/location`) is responsible for mapping the Android type
 * into this one. Nullable fields mean "not available/invalid", never a NaN
 * sentinel.
 *
 * [timestampNanos] is a monotonic timestamp in nanoseconds.
 */
data class LocationReading(
    val lat: Double,
    val lon: Double,
    val timestampNanos: Long,
    val altitudeEllipsoidM: Double? = null,
    val accuracyH: Double? = null,
    val accuracyV: Double? = null,
    val speedMps: Double? = null,
    val bearing: Float? = null,
)
