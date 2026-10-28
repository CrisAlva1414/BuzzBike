package com.buzzbike.app.domain.sync

import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [RidePayloadBuilder] (ADR-003 data minimization).
 *
 * No Android dependencies: the builder only sees domain models, so these tests
 * pin down ordering, validation, decimation, the waypoint cap and the
 * elevation fallback.
 */
class RidePayloadBuilderTest {
    private fun point(
        seconds: Long,
        lat: Double = BASE_LAT,
        lon: Double = BASE_LON,
        altitudeMslM: Double? = null,
        altitudeEllipsoidM: Double? = null,
    ): TrackPoint =
        TrackPoint(
            timestampMonotonicNanos = seconds * NANOS_PER_SECOND,
            lat = lat,
            lon = lon,
            altitudeMslM = altitudeMslM,
            altitudeEllipsoidM = altitudeEllipsoidM,
        )

    private fun input(points: List<TrackPoint>): RideSyncInput =
        RideSyncInput(
            rideId = RIDE_ID,
            startTimeMillis = START_MILLIS,
            endTimeMillis = START_MILLIS + END_OFFSET_MILLIS,
            descriptors = descriptors(),
            elevationGainM = 12.5,
            elevationLossM = 4.0,
            caloriesKcal = 250.0,
            caloriesLowKcal = 175.0,
            caloriesHighKcal = 325.0,
            calorieModel = "COMPENDIUM_VELOCITY",
            maxSpeedMps = 9.5,
            points = points,
        )

    private fun descriptors(): RideDescriptors =
        RideDescriptors(
            distanceM = 1_000.0,
            elapsedSeconds = 100,
            movingSeconds = 90,
            stoppedSeconds = 10,
            avgMovingSpeedMps = 11.1,
            avgSpeedMps = 10.0,
            maxSpeedMps = 9.5,
            stopCount = 1,
            stopDurationsSeconds = listOf(10L),
            acceptedPoints = 100,
            rejectedPoints = 0,
            gapCount = 0,
            distanceIsLowerBound = false,
            modelVersion = "test",
        )

    private fun offsetMillis(waypoint: Waypoint): Long = waypoint.timestampMillis - START_MILLIS

    @Test
    fun given_oneHzTrace_when_decimated_then_keepsEveryTenSecondsAndLastPoint() {
        val points = List(100) { index -> point(index.toLong()) }

        val payload = RidePayloadBuilder.build(input(points), decimationSeconds = 10L)

        val expectedOffsets =
            listOf(
                0L,
                10_000L,
                20_000L,
                30_000L,
                40_000L,
                50_000L,
                60_000L,
                70_000L,
                80_000L,
                90_000L,
                99_000L,
            )
        assertEquals(expectedOffsets, payload.waypoints.map(::offsetMillis))
        assertEquals(11, payload.waypoints.size)
        assertTrue(payload.waypoints.size < points.size)
    }

    @Test
    fun given_unorderedPoints_when_built_then_waypointsAreOrderedByTime() {
        val points = listOf(point(5), point(0), point(9), point(2), point(7))

        val payload = RidePayloadBuilder.build(input(points), decimationSeconds = 1L)

        val offsets = payload.waypoints.map(::offsetMillis)
        assertEquals(listOf(0L, 2_000L, 5_000L, 7_000L, 9_000L), offsets)
        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun given_invalidCoordinates_when_built_then_theyAreDiscarded() {
        val points =
            listOf(
                point(0),
                point(1, lat = Double.NaN),
                point(2, lon = Double.POSITIVE_INFINITY),
                point(3, lat = 91.0),
                point(4, lon = -181.0),
                point(5),
            )

        val payload = RidePayloadBuilder.build(input(points), decimationSeconds = 0L)

        assertEquals(listOf(0L, 5_000L), payload.waypoints.map(::offsetMillis))
    }

    @Test
    fun given_smallMaxWaypoints_when_built_then_capIsRespectedKeepingFirstAndLast() {
        val points = List(100) { index -> point(index.toLong()) }

        val payload =
            RidePayloadBuilder.build(
                input(points),
                decimationSeconds = 0L,
                maxWaypoints = 10,
            )

        val offsets = payload.waypoints.map(::offsetMillis)
        assertEquals(10, payload.waypoints.size)
        assertEquals(0L, offsets.first())
        assertEquals(99_000L, offsets.last())
        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun given_altitudeSources_when_built_then_mslIsPreferredThenEllipsoidThenNull() {
        val points =
            listOf(
                point(0, altitudeMslM = 100.0),
                point(1, altitudeMslM = Double.NaN, altitudeEllipsoidM = 200.0),
                point(2),
            )

        val payload = RidePayloadBuilder.build(input(points), decimationSeconds = 0L)

        assertEquals(
            listOf(100.0, 200.0, null),
            payload.waypoints.map { it.elevationM },
        )
    }

    @Test
    fun given_moreThanSixDecimals_when_built_then_coordinatesAreRounded() {
        val points =
            listOf(
                point(0, lat = 12.3456784, lon = -45.1234569),
            )

        val payload = RidePayloadBuilder.build(input(points), decimationSeconds = 0L)

        val waypoint = payload.waypoints.single()
        assertEquals(12.345678, waypoint.lat, 0.0)
        assertEquals(-45.123457, waypoint.lon, 0.0)
    }

    @Test
    fun given_completeCalorieBand_when_built_thenPayloadCarriesRangeAndModel() {
        val payload = RidePayloadBuilder.build(input(emptyList()))

        val calories = requireNotNull(payload.calories)
        assertEquals(250.0, calories.estimateKcal, 0.0)
        assertEquals(175.0, calories.lowKcal, 0.0)
        assertEquals(325.0, calories.highKcal, 0.0)
        assertEquals("COMPENDIUM_VELOCITY", calories.model)
    }

    @Test
    fun given_missingCalorieModel_when_built_thenCaloriesAreOmitted() {
        val payload =
            RidePayloadBuilder.build(
                input(emptyList()).copy(calorieModel = null),
            )

        assertEquals(null, payload.calories)
    }

    @Test
    fun given_missingCalorieInterval_when_built_thenCaloriesAreOmitted() {
        val payload =
            RidePayloadBuilder.build(
                input(emptyList()).copy(caloriesLowKcal = null, caloriesHighKcal = null),
            )

        assertEquals(null, payload.calories)
    }

    @Test
    fun given_nonFiniteCalories_when_built_thenCaloriesAreOmitted() {
        val payload =
            RidePayloadBuilder.build(
                input(emptyList()).copy(
                    caloriesKcal = Double.NaN,
                    caloriesLowKcal = Double.NEGATIVE_INFINITY,
                    caloriesHighKcal = Double.POSITIVE_INFINITY,
                ),
            )

        assertEquals(null, payload.calories)
    }

    @Test
    fun given_invertedCalorieBand_when_built_thenCaloriesAreOmitted() {
        val payload =
            RidePayloadBuilder.build(
                input(emptyList()).copy(caloriesLowKcal = 400.0, caloriesHighKcal = 100.0),
            )

        assertEquals(null, payload.calories)
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
        private const val START_MILLIS = 1_700_000_000_000L
        private const val END_OFFSET_MILLIS = 100_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BASE_LAT = -33.4489
        private const val BASE_LON = -70.6693
    }
}
