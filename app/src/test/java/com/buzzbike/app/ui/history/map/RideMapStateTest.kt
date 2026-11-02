package com.buzzbike.app.ui.history.map

import com.buzzbike.app.domain.history.GeoPoint
import com.buzzbike.app.domain.history.TrackGeoJson
import com.buzzbike.app.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** JVM tests for the pure state machine behind the ride map. */
class RideMapStateTest {
    @Test
    fun given_zeroPoints_when_mapStateFor_then_NoTrack() {
        assertEquals(RideMapUiState.NoTrack, mapStateFor(emptyList()))
    }

    @Test
    fun given_onePoint_when_mapStateFor_then_NoTrack() {
        assertEquals(RideMapUiState.NoTrack, mapStateFor(listOf(point(lat = -33.4, lon = -70.6))))
    }

    @Test
    fun given_oneValidAndOneInvalidPoint_when_mapStateFor_then_NoTrack() {
        val state =
            mapStateFor(
                listOf(
                    point(lat = -33.4, lon = -70.6),
                    point(lat = Double.NaN, lon = -70.7),
                ),
            )

        assertEquals(RideMapUiState.NoTrack, state)
    }

    @Test
    fun given_twoValidPoints_when_mapStateFor_then_LoadingWithGeoJson() {
        val points =
            listOf(
                point(lat = -33.4, lon = -70.6),
                point(lat = -33.5, lon = -70.7),
            )

        val expectedPoints =
            listOf(
                GeoPoint(lat = -33.4, lon = -70.6),
                GeoPoint(lat = -33.5, lon = -70.7),
            )
        val expected =
            RideMapUiState.Loading(
                points = expectedPoints,
                geoJson = requireNotNull(TrackGeoJson.lineStringFeatureCollection(expectedPoints)),
            )

        assertEquals(expected, mapStateFor(points))
    }

    @Test
    fun given_twoValidPointsAndNoNetwork_when_mapStateFor_then_FailedWithPayload() {
        val points =
            listOf(
                point(lat = -33.4, lon = -70.6),
                point(lat = -33.5, lon = -70.7),
            )
        val expectedPoints =
            listOf(
                GeoPoint(lat = -33.4, lon = -70.6),
                GeoPoint(lat = -33.5, lon = -70.7),
            )

        val state = mapStateFor(points, isOnline = false) as RideMapUiState.Failed

        assertEquals(expectedPoints, state.points)
        assertEquals(
            requireNotNull(TrackGeoJson.lineStringFeatureCollection(expectedPoints)),
            state.geoJson,
        )
        assertEquals(0, state.attempt)
    }

    @Test
    fun given_noNetworkAndNoTrack_when_mapStateFor_then_NoTrackWins() {
        assertEquals(RideMapUiState.NoTrack, mapStateFor(emptyList(), isOnline = false))
    }

    @Test
    fun given_Loading_when_onStyleTimeout_then_FailedKeepsPayload() {
        val loading = loading()

        val timedOut = loading.onStyleTimeout()

        assertEquals(
            RideMapUiState.Failed(points = loading.points, geoJson = loading.geoJson, attempt = loading.attempt),
            timedOut,
        )
    }

    @Test
    fun given_Failed_when_onStyleTimeout_then_stateIsUnchanged() {
        val failed = loading().onFailure()

        assertSame(failed, failed.onStyleTimeout())
    }

    @Test
    fun given_invalidFixes_when_validMapPoints_then_onlyRenderablePointsRemain() {
        val points =
            listOf(
                point(lat = Double.NaN, lon = 0.0),
                point(lat = 0.0, lon = Double.NEGATIVE_INFINITY),
                point(lat = 90.1, lon = 0.0),
                point(lat = 0.0, lon = -180.1),
                point(lat = -33.4, lon = -70.6),
            )

        val valid = validMapPoints(points)

        assertEquals(listOf(GeoPoint(lat = -33.4, lon = -70.6)), valid)
    }

    @Test
    fun given_validPoints_when_validMapPoints_then_latLonAreNotSwapped() {
        val valid = validMapPoints(listOf(point(lat = -33.4, lon = -70.6)))

        assertEquals(GeoPoint(lat = -33.4, lon = -70.6), valid.single())
    }

    @Test
    fun given_Loading_when_onFailure_then_FailedKeepsPayload() {
        val loading = loading()

        val failed = loading.onFailure()

        assertEquals(
            RideMapUiState.Failed(points = loading.points, geoJson = loading.geoJson, attempt = loading.attempt),
            failed,
        )
    }

    @Test
    fun given_Failed_when_onFailure_then_stateIsUnchanged() {
        val failed = loading().onFailure()

        assertSame(failed, failed.onFailure())
    }

    @Test
    fun given_NoTrack_when_onFailure_then_stateIsUnchanged() {
        assertSame(RideMapUiState.NoTrack, RideMapUiState.NoTrack.onFailure())
    }

    @Test
    fun given_Failed_when_onRetry_then_LoadingWithNextAttempt() {
        val failed = loading().onFailure() as RideMapUiState.Failed

        val retried = failed.onRetry() as RideMapUiState.Loading

        assertEquals(failed.points, retried.points)
        assertEquals(failed.geoJson, retried.geoJson)
        assertEquals(failed.attempt + 1, retried.attempt)
    }

    @Test
    fun given_Loading_when_onRetry_then_stateIsUnchanged() {
        val loading = loading()

        assertSame(loading, loading.onRetry())
    }

    @Test
    fun given_NoTrack_when_onRetry_then_stateIsUnchanged() {
        assertSame(RideMapUiState.NoTrack, RideMapUiState.NoTrack.onRetry())
    }

    private fun loading() =
        mapStateFor(
            listOf(
                point(lat = -33.4, lon = -70.6),
                point(lat = -33.5, lon = -70.7),
            ),
        ) as RideMapUiState.Loading

    private fun point(
        lat: Double,
        lon: Double,
    ): TrackPoint = TrackPoint(timestampMonotonicNanos = 0L, lat = lat, lon = lon)
}
