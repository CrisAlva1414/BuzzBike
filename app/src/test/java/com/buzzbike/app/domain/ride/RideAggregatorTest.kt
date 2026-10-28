package com.buzzbike.app.domain.ride

import com.buzzbike.app.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [RideAggregator] (ADR-005 §5–§6).
 *
 * Points are built on a north-south line; `positionSpeedsMps` controls the
 * geometric step independently from the reported `speedMps`, so a speed outlier
 * can survive trace cleanup. No Android dependencies.
 */
class RideAggregatorTest {
    private fun point(
        seconds: Long,
        metersNorth: Double,
        speedMps: Double,
        accuracyH: Double = 5.0,
    ): TrackPoint =
        TrackPoint(
            timestampMonotonicNanos = seconds * NANOS_PER_SECOND,
            lat = BASE_LAT + metersNorth / METERS_PER_LAT_DEGREE,
            lon = BASE_LON,
            accuracyH = accuracyH,
            speedMps = speedMps,
        )

    private fun trace(
        speedsMps: List<Double>,
        positionSpeedsMps: List<Double> = speedsMps,
    ): List<TrackPoint> {
        var meters = 0.0
        return speedsMps.mapIndexed { index, speed ->
            if (index > 0) {
                meters += positionSpeedsMps[index]
            }
            point(
                seconds = index.toLong(),
                metersNorth = meters,
                speedMps = speed,
            )
        }
    }

    @Test
    fun given_straightLine1km_when_described_then_distanceWithin1Percent() {
        val cleaned = TraceCleaner.clean(trace(List(200) { 5.0 }))

        val descriptors = RideAggregator.describe(cleaned)

        assertEquals(1000.0, descriptors.distanceM, 10.0)
        assertEquals(200.0, descriptors.movingSeconds.toDouble(), 1.0)
        assertEquals(5.0, descriptors.avgMovingSpeedMps, 0.01)
        assertEquals(5.0, descriptors.avgSpeedMps, 0.01)
    }

    @Test
    fun given_stationaryJitter_when_described_then_distanceZero() {
        val points =
            List(60) { index ->
                point(
                    seconds = index.toLong(),
                    metersNorth = if (index % 2 == 0) 3.0 else -3.0,
                    speedMps = 0.0,
                )
            }

        val descriptors = RideAggregator.describe(TraceCleaner.clean(points))

        assertEquals(0.0, descriptors.distanceM, 1.0)
        assertEquals(0L, descriptors.movingSeconds)
    }

    @Test
    fun given_trafficLightStop_when_described_then_avgMovingGreaterThanAvgSpeed() {
        val speeds = List(60) { 5.0 } + List(30) { 0.0 } + List(60) { 5.0 }

        val descriptors = RideAggregator.describe(TraceCleaner.clean(trace(speeds)))

        assertTrue(descriptors.avgMovingSpeedMps > descriptors.avgSpeedMps)
        assertEquals(30.0, descriptors.stoppedSeconds.toDouble(), 1.0)
    }

    @Test
    fun given_speedOutlier_when_described_then_maxSpeedRobust() {
        val speeds = MutableList(120) { 5.0 }
        speeds[60] = 50.0

        val points = trace(speedsMps = speeds, positionSpeedsMps = List(120) { 5.0 })
        val descriptors = RideAggregator.describe(TraceCleaner.clean(points))

        assertTrue(descriptors.maxSpeedMps < 10.0)
    }

    @Test
    fun given_rejectedPoints_when_described_then_distanceIsLowerBound() {
        val points =
            listOf(
                point(seconds = 0, metersNorth = 0.0, speedMps = 5.0),
                point(seconds = 1, metersNorth = 5.0, speedMps = 5.0, accuracyH = 31.0),
                point(seconds = 2, metersNorth = 10.0, speedMps = 5.0),
            )

        val descriptors = RideAggregator.describe(TraceCleaner.clean(points))

        assertTrue(descriptors.distanceIsLowerBound)
        assertEquals(1, descriptors.rejectedPoints)
    }

    private companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BASE_LAT = -33.4489
        private const val BASE_LON = -70.6693
        private const val METERS_PER_LAT_DEGREE = 111_194.9
    }
}
