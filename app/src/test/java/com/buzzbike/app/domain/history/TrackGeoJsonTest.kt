package com.buzzbike.app.domain.history

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JVM tests for [TrackGeoJson].
 *
 * Robolectric is used only because `org.json` on the JVM is an Android stub;
 * the parser itself is the real implementation bundled with Robolectric. The
 * object under test stays pure (no Android imports).
 */
@RunWith(RobolectricTestRunner::class)
class TrackGeoJsonTest {
    @Test
    fun given_twoPoints_when_built_then_coordinatesAreLonLatInOrder() {
        val geoJson =
            TrackGeoJson.lineStringFeatureCollection(
                listOf(
                    GeoPoint(lat = -33.4489, lon = -70.6693),
                    GeoPoint(lat = -33.4372, lon = -70.6506),
                ),
            )

        val coordinates = coordinatesOf(requireNotNull(geoJson))
        assertEquals(2, coordinates.length())
        assertCoordinate(coordinates, index = 0, lon = -70.6693, lat = -33.4489)
        assertCoordinate(coordinates, index = 1, lon = -70.6506, lat = -33.4372)
    }

    @Test
    fun given_zeroPoints_when_built_then_null() {
        assertNull(TrackGeoJson.lineStringFeatureCollection(emptyList()))
    }

    @Test
    fun given_onePoint_when_built_then_null() {
        assertNull(
            TrackGeoJson.lineStringFeatureCollection(
                listOf(GeoPoint(lat = -33.4489, lon = -70.6693)),
            ),
        )
    }

    @Test
    fun given_negativeCoordinates_when_built_then_signIsPreserved() {
        val geoJson =
            TrackGeoJson.lineStringFeatureCollection(
                listOf(
                    GeoPoint(lat = -33.4, lon = -70.6),
                    GeoPoint(lat = -33.5, lon = -70.7),
                ),
            )

        val coordinates = coordinatesOf(requireNotNull(geoJson))
        assertCoordinate(coordinates, index = 0, lon = -70.6, lat = -33.4)
        assertCoordinate(coordinates, index = 1, lon = -70.7, lat = -33.5)
    }

    @Test
    fun given_excessDecimals_when_built_then_coordinatesUseSixDecimals() {
        val geoJson =
            requireNotNull(
                TrackGeoJson.lineStringFeatureCollection(
                    listOf(
                        GeoPoint(lat = -33.9876543, lon = -70.1234567),
                        GeoPoint(lat = 0.0000009, lon = 0.0000001),
                    ),
                ),
            )

        assertTrue(geoJson.contains("[-70.123457,-33.987654]"))
        assertTrue(geoJson.contains("[0.000000,0.000001]"))
    }

    @Test
    fun given_document_when_parsed_then_isFeatureCollectionWithLineString() {
        val geoJson =
            requireNotNull(
                TrackGeoJson.lineStringFeatureCollection(
                    listOf(
                        GeoPoint(lat = -33.4489, lon = -70.6693),
                        GeoPoint(lat = -33.4372, lon = -70.6506),
                    ),
                ),
            )

        val root = JSONObject(geoJson)
        assertEquals("FeatureCollection", root.getString("type"))
        val feature = root.getJSONArray("features").getJSONObject(0)
        assertEquals("Feature", feature.getString("type"))
        val geometry = feature.getJSONObject("geometry")
        assertEquals("LineString", geometry.getString("type"))
        assertEquals(2, geometry.getJSONArray("coordinates").length())
    }

    @Test
    fun given_nanInfinityAndOutOfRange_when_built_then_thosePointsAreDiscarded() {
        val geoJson =
            requireNotNull(
                TrackGeoJson.lineStringFeatureCollection(
                    listOf(
                        GeoPoint(lat = Double.NaN, lon = 0.0),
                        GeoPoint(lat = 0.0, lon = Double.POSITIVE_INFINITY),
                        GeoPoint(lat = 91.0, lon = 0.0),
                        GeoPoint(lat = 0.0, lon = -181.0),
                        GeoPoint(lat = -33.4, lon = -70.6),
                        GeoPoint(lat = -33.5, lon = -70.7),
                    ),
                ),
            )

        val coordinates = coordinatesOf(geoJson)
        assertEquals(2, coordinates.length())
        assertCoordinate(coordinates, index = 0, lon = -70.6, lat = -33.4)
        assertCoordinate(coordinates, index = 1, lon = -70.7, lat = -33.5)
        assertFalse(geoJson.contains("NaN"))
        assertFalse(geoJson.contains("Infinity"))
    }

    @Test
    fun given_onlyInvalidPoints_when_built_then_null() {
        assertNull(
            TrackGeoJson.lineStringFeatureCollection(
                listOf(
                    GeoPoint(lat = Double.NaN, lon = 0.0),
                    GeoPoint(lat = 100.0, lon = 0.0),
                ),
            ),
        )
    }

    private fun coordinatesOf(geoJson: String) =
        JSONObject(geoJson)
            .getJSONArray("features")
            .getJSONObject(0)
            .getJSONObject("geometry")
            .getJSONArray("coordinates")

    private fun assertCoordinate(
        coordinates: org.json.JSONArray,
        index: Int,
        lon: Double,
        lat: Double,
    ) {
        val coordinate = coordinates.getJSONArray(index)
        assertEquals(lon, coordinate.getDouble(0), 0.000001)
        assertEquals(lat, coordinate.getDouble(1), 0.000001)
    }
}
