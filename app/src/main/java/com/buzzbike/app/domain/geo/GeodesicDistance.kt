package com.buzzbike.app.domain.geo

import com.buzzbike.app.domain.model.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port for geodesic distance between two [TrackPoint]s, in meters.
 *
 * Domain code depends on this interface instead of `android.location.Location`,
 * so `domain/` stays JVM-pure (AGENTS.md §3/§8). Production injects an adapter
 * that delegates to `Location.distanceBetween` (WGS84 ellipsoid); see ADR-005 §5.
 */
fun interface GeodesicDistance {
    fun meters(
        a: TrackPoint,
        b: TrackPoint,
    ): Double
}

/**
 * Spherical haversine implementation, intended for JVM-only tests and
 * reference fixtures.
 *
 * **Accuracy:** a sphere with a mean Earth radius introduces roughly
 * 0.3–0.5% error versus the WGS84 ellipsoid. Production uses
 * `Location.distanceBetween` (WGS84) behind [GeodesicDistance]; this object
 * must not be used to claim production-grade distance accuracy.
 *
 * No Android imports.
 */
object HaversineDistance : GeodesicDistance {
    private const val EARTH_RADIUS_M = 6_371_008.8

    override fun meters(
        a: TrackPoint,
        b: TrackPoint,
    ): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(b.lon - a.lon)

        val sinHalfDeltaLat = sin(deltaLat / 2)
        val sinHalfDeltaLon = sin(deltaLon / 2)
        val h =
            sinHalfDeltaLat * sinHalfDeltaLat +
                cos(lat1) * cos(lat2) * sinHalfDeltaLon * sinHalfDeltaLon

        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
