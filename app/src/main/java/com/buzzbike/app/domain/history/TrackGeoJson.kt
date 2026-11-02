package com.buzzbike.app.domain.history

import java.util.Locale

private const val MIN_LINE_STRING_POINTS = 2
private const val COORDINATE_DECIMALS = 6
private const val COORDINATE_FORMAT = "%.${COORDINATE_DECIMALS}f"
private const val MIN_LATITUDE = -90.0
private const val MAX_LATITUDE = 90.0
private const val MIN_LONGITUDE = -180.0
private const val MAX_LONGITUDE = 180.0

/**
 * `FEATURE_COLLECTION_PREFIX + coordinates + FEATURE_COLLECTION_SUFFIX` is a
 * valid GeoJSON [RFC 7946](https://tools.ietf.org/html/rfc7946) document
 * parsed by MapLibre's `GeoJsonSource` without any extra serialization
 * dependency in the domain layer.
 */
private const val FEATURE_COLLECTION_PREFIX =
    """{"type":"FeatureCollection","features":[{""" +
        """"type":"Feature","properties":{},"geometry":{""" +
        """"type":"LineString","coordinates":["""

private const val FEATURE_COLLECTION_SUFFIX = "]}}]}"

/** WGS84 coordinate for pure GeoJSON rendering. Latitude/longitude in degrees. */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
)

/**
 * Pure GeoJSON builder for a ride trace.
 *
 * This object lives in `domain` and intentionally imports nothing from
 * Android, MapLibre, or `org.json`: the screen only needs the final string and
 * the JVM suite can verify it without a device. Coordinates follow RFC 7946
 * (`[lon, lat]`, not `[lat, lon]`); points outside WGS84 and non-finite values
 * are dropped before building the line, and fewer than two remaining points
 * yield `null` because a `LineString` requires at least two positions.
 */
object TrackGeoJson {
    /**
     * Returns the GeoJSON `FeatureCollection` for [points], or `null` when
     * fewer than two valid points remain.
     *
     * Coordinates are rendered with [COORDINATE_DECIMALS] decimals under
     * [Locale.ROOT], which keeps the output deterministic and avoids the
     * scientific notation that `Double.toString` may produce for tiny values.
     */
    fun lineStringFeatureCollection(points: List<GeoPoint>): String? {
        val validPoints = points.filter { it.isValid() }
        if (validPoints.size < MIN_LINE_STRING_POINTS) {
            return null
        }
        val coordinates = validPoints.joinToString(separator = ",") { it.toJsonCoordinate() }
        return FEATURE_COLLECTION_PREFIX + coordinates + FEATURE_COLLECTION_SUFFIX
    }

    private fun GeoPoint.isValid(): Boolean =
        lat.isFinite() &&
            lon.isFinite() &&
            lat in MIN_LATITUDE..MAX_LATITUDE &&
            lon in MIN_LONGITUDE..MAX_LONGITUDE

    private fun GeoPoint.toJsonCoordinate(): String = "[" + format(lon) + "," + format(lat) + "]"

    private fun format(value: Double): String = String.format(Locale.ROOT, COORDINATE_FORMAT, value)
}
