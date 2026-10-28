package com.buzzbike.app.domain.ride

import com.buzzbike.app.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases for the domain slice that the existing tests did not cover.
 *
 * These tests exercise empty/single-point traces, boundary gaps, zero-duration
 * rides, non-finite hostile inputs, and the "no Doppler" contract from
 * ADR-005 §1–§3. JVM-pure, no Android dependencies.
 */
class RideDomainEdgeCaseTest {
    private fun pointAt(
        nanos: Long,
        metersNorth: Double = 0.0,
        speedMps: Double? = 5.0,
        accuracyH: Double = 5.0,
    ): TrackPoint =
        TrackPoint(
            timestampMonotonicNanos = nanos,
            lat = BASE_LAT + metersNorth / METERS_PER_LAT_DEGREE,
            lon = BASE_LON,
            accuracyH = accuracyH,
            speedMps = speedMps,
        )

    /**
     * Builds points one second apart (except optionally after [gapAfterIndex],
     * where the step is [gapSeconds]), advancing north at [positionSpeedMps].
     */
    private fun lineTrace(
        count: Int,
        positionSpeedMps: Double,
        reportedSpeedMps: Double? = positionSpeedMps,
        gapAfterIndex: Int? = null,
        gapSeconds: Long = 0,
    ): List<TrackPoint> {
        var meters = 0.0
        var nanos = 0L
        return (0 until count).map { index ->
            if (index > 0) {
                val stepSeconds =
                    if (gapAfterIndex != null && index == gapAfterIndex + 1) gapSeconds else 1L
                nanos += stepSeconds * NANOS_PER_SECOND
                meters += positionSpeedMps * stepSeconds
            }
            pointAt(nanos, meters, reportedSpeedMps)
        }
    }

    @Test
    fun given_emptyTrace_when_described_thenZerosWithoutCrash() {
        val cleaned = TraceCleaner.clean(emptyList())

        val descriptors = RideAggregator.describe(cleaned)

        assertTrue(cleaned.points.isEmpty())
        assertTrue(cleaned.segments.isEmpty())
        assertEquals(0, cleaned.rejectedCount)
        assertEquals(0, cleaned.gapCount)
        assertFalse(cleaned.distanceIsLowerBound)
        assertEquals(0.0, descriptors.distanceM, 0.0)
        assertEquals(0L, descriptors.elapsedSeconds)
        assertEquals(0L, descriptors.movingSeconds)
        assertEquals(0L, descriptors.stoppedSeconds)
        assertEquals(0, descriptors.stopCount)
        assertEquals(0, descriptors.acceptedPoints)
        assertEquals(0, descriptors.rejectedPoints)
        assertEquals(0, descriptors.gapCount)
        assertEquals(0.0, descriptors.maxSpeedMps, 0.0)
    }

    @Test
    fun given_singlePoint_when_described_thenZerosWithoutCrash() {
        val cleaned = TraceCleaner.clean(listOf(pointAt(nanos = 0L)))

        val descriptors = RideAggregator.describe(cleaned)

        assertEquals(1, cleaned.points.size)
        assertEquals(1, cleaned.segments.size)
        assertEquals(1, descriptors.acceptedPoints)
        assertEquals(0L, descriptors.elapsedSeconds)
        assertEquals(0L, descriptors.movingSeconds)
        assertEquals(0L, descriptors.stoppedSeconds)
        assertEquals(0.0, descriptors.distanceM, 0.0)
        assertEquals(0.0, descriptors.avgMovingSpeedMps, 0.0)
        assertEquals(0.0, descriptors.avgSpeedMps, 0.0)
        assertEquals(0.0, descriptors.maxSpeedMps, 0.0)
        assertEquals(0, descriptors.stopCount)
    }

    @Test
    fun given_zeroDurationTrace_when_cleaned_thenDuplicateTimestampCollapsed() {
        val cleaned =
            TraceCleaner.clean(
                listOf(
                    pointAt(nanos = 0L, metersNorth = 0.0),
                    pointAt(nanos = 0L, metersNorth = 5.0),
                ),
            )

        val descriptors = RideAggregator.describe(cleaned)

        assertEquals(1, cleaned.points.size)
        assertEquals(0L, descriptors.elapsedSeconds)
        assertEquals(0L, descriptors.movingSeconds)
        assertEquals(0.0, descriptors.distanceM, 0.0)
    }

    @Test
    fun given_oneSecondRide_when_described_thenNoMovingAndFiniteDescriptors() {
        val cleaned = TraceCleaner.clean(lineTrace(count = 2, positionSpeedMps = 5.0))

        val descriptors = RideAggregator.describe(cleaned)

        assertEquals(2, descriptors.acceptedPoints)
        assertEquals(1L, descriptors.elapsedSeconds)
        assertEquals(0L, descriptors.movingSeconds)
        assertEquals(1L, descriptors.stoppedSeconds)
        assertEquals(0.0, descriptors.distanceM, 1e-9)
        assertTrue(descriptors.maxSpeedMps.isFinite())
    }

    @Test
    fun given_tenOneSecondSamples_when_movingFromStart_thenMovingIsNineSeconds() {
        val state = MovingTimeEstimator.estimate(lineTrace(count = 10, positionSpeedMps = 5.0))

        val movingSeconds = state.movingIntervals.sumOf { it.endNanos - it.startNanos } / NANOS_PER_SECOND

        assertEquals(9L, movingSeconds)
    }

    @Test
    fun given_gap45s_when_described_thenMovingAbout120AndGapNotAccumulated() {
        val trace = lineTrace(count = 120, positionSpeedMps = 5.555, gapAfterIndex = 59, gapSeconds = 45)

        val cleaned = TraceCleaner.clean(trace)
        val descriptors = RideAggregator.describe(cleaned)

        assertEquals(120, cleaned.points.size)
        assertEquals(1, cleaned.gapCount)
        assertTrue(cleaned.distanceIsLowerBound)
        assertEquals(163L, cleaned.points.last().timestampMonotonicNanos / NANOS_PER_SECOND)
        assertEquals(163L, descriptors.elapsedSeconds)
        assertEquals(45L, descriptors.stoppedSeconds)
        assertTrue(
            "moving should be ~118-120 s (not elapsed=163 s), was ${descriptors.movingSeconds}",
            descriptors.movingSeconds in 118L..120L,
        )
        assertTrue(
            "distance across the 45 s gap must not be accumulated, was ${descriptors.distanceM}",
            descriptors.distanceM in 650.0..660.0,
        )
    }

    @Test
    fun given_stationaryJitterWithoutReportedSpeed_when_described_thenNotMoving() {
        // ADR-005 §1/§3: a fix without usable speed is a gap, not a zero-speed
        // sample, and consecutive-fix displacement is explicitly forbidden as a
        // speed source. Stationary GPS jitter must not auto-resume movement.
        val trace =
            (0 until 60).map { index ->
                pointAt(
                    nanos = index * NANOS_PER_SECOND,
                    metersNorth = if (index % 2 == 0) 3.0 else -3.0,
                    speedMps = null,
                )
            }

        val cleaned = TraceCleaner.clean(trace)
        val descriptors = RideAggregator.describe(cleaned)
        val details =
            "moving=${descriptors.movingSeconds}s, stopped=${descriptors.stoppedSeconds}s, " +
                "distance=${descriptors.distanceM}m, stops=${descriptors.stopCount}"

        assertTrue(
            "stationary jitter must not create moving time or distance ($details)",
            descriptors.movingSeconds == 0L && descriptors.distanceM <= 1.0,
        )
    }

    @Test
    fun given_nonFiniteOrInvalidInputs_when_cleaned_thenTheyAreRejected() {
        val before = pointAt(nanos = 0L, metersNorth = 0.0)
        val after = pointAt(nanos = 2L * NANOS_PER_SECOND, metersNorth = 10.0)
        val invalid =
            mapOf(
                "lat=NaN" to before.copy(timestampMonotonicNanos = NANOS_PER_SECOND, lat = Double.NaN),
                "lat=+Inf" to
                    before.copy(
                        timestampMonotonicNanos = NANOS_PER_SECOND,
                        lat = Double.POSITIVE_INFINITY,
                    ),
                "lon=NaN" to before.copy(timestampMonotonicNanos = NANOS_PER_SECOND, lon = Double.NaN),
                "lon=+Inf" to
                    before.copy(
                        timestampMonotonicNanos = NANOS_PER_SECOND,
                        lon = Double.POSITIVE_INFINITY,
                    ),
                "accuracyH=NaN" to
                    before.copy(
                        timestampMonotonicNanos = NANOS_PER_SECOND,
                        accuracyH = Double.NaN,
                    ),
                "accuracyH=-Inf" to
                    before.copy(
                        timestampMonotonicNanos = NANOS_PER_SECOND,
                        accuracyH = Double.NEGATIVE_INFINITY,
                    ),
                "accuracyH=-1" to
                    before.copy(timestampMonotonicNanos = NANOS_PER_SECOND, accuracyH = -1.0),
            )

        val leaked =
            invalid
                .filterValues { hostile ->
                    TraceCleaner.clean(listOf(before, hostile, after)).points.any { it === hostile }
                }.keys
        val nanTrace =
            listOf(
                before,
                invalid.getValue("lat=NaN"),
                pointAt(nanos = 2L * NANOS_PER_SECOND, metersNorth = 10.0),
                pointAt(nanos = 3L * NANOS_PER_SECOND, metersNorth = 15.0),
                pointAt(nanos = 4L * NANOS_PER_SECOND, metersNorth = 20.0),
                pointAt(nanos = 5L * NANOS_PER_SECOND, metersNorth = 25.0),
            )
        val nanDescriptors = RideAggregator.describe(TraceCleaner.clean(nanTrace))

        assertTrue(
            "non-finite/invalid points leaked into the cleaned trace: $leaked; " +
                "NaN latitude produced distanceM=${nanDescriptors.distanceM}, " +
                "maxSpeedMps=${nanDescriptors.maxSpeedMps}",
            leaked.isEmpty(),
        )
    }

    @Test
    fun given_infiniteReportedSpeed_when_described_thenSpeedsAreFinite() {
        val trace =
            lineTrace(
                count = 30,
                positionSpeedMps = 0.0,
                reportedSpeedMps = Double.POSITIVE_INFINITY,
            )

        val cleaned = TraceCleaner.clean(trace)
        val descriptors = RideAggregator.describe(cleaned)

        assertTrue(
            "maxSpeedMps must stay finite, was ${descriptors.maxSpeedMps}",
            descriptors.maxSpeedMps.isFinite(),
        )
        assertTrue(
            "avgMovingSpeedMps must stay finite, was ${descriptors.avgMovingSpeedMps}",
            descriptors.avgMovingSpeedMps.isFinite(),
        )
    }

    @Test
    fun given_accuracyBoundary_when_cleaned_thenExactly30IsAccepted() {
        val cleaned =
            TraceCleaner.clean(
                listOf(
                    pointAt(nanos = 0L, metersNorth = 0.0, accuracyH = 30.0),
                    pointAt(nanos = NANOS_PER_SECOND, metersNorth = 5.0, accuracyH = 30.0),
                ),
            )

        assertEquals(2, cleaned.points.size)
        assertEquals(0, cleaned.rejectedCount)
    }

    @Test
    fun given_gapBoundaries_when_cleaned_thenOnlyOver60sCutsSegment() {
        val exactly5s =
            TraceCleaner.clean(
                listOf(
                    pointAt(nanos = 0L, metersNorth = 0.0),
                    pointAt(nanos = 5L * NANOS_PER_SECOND, metersNorth = 5.555),
                ),
            )
        val exactly60s =
            TraceCleaner.clean(
                listOf(
                    pointAt(nanos = 0L, metersNorth = 0.0),
                    pointAt(nanos = 60L * NANOS_PER_SECOND, metersNorth = 66.6),
                ),
            )

        assertEquals(1, exactly5s.segments.size)
        assertEquals(0, exactly5s.gapCount)
        assertFalse(exactly5s.distanceIsLowerBound)
        assertEquals(1, exactly60s.segments.size)
        assertEquals(1, exactly60s.gapCount)
        assertTrue(exactly60s.distanceIsLowerBound)
    }

    private fun movingSeconds(state: MovingState): Double =
        state.movingIntervals.sumOf { (it.endNanos - it.startNanos).toDouble() / NANOS_PER_SECOND }

    @Test
    fun given_gapOver60sWithLowSpeedAfterGap_when_estimated_thenNewSegmentStartsStopped() {
        // ADR-005 §2/§4: a gap >60 s cuts the segment and the new segment starts
        // in STOPPED, so slow samples right after the gap must not count as moving.
        val beforeGap = lineTrace(count = 30, positionSpeedMps = 5.0)
        val lastMeters = 5.0 * 29
        val afterGapMeters = lastMeters + 5.0 * 61
        val gapPoint = pointAt(nanos = 90L * NANOS_PER_SECOND, metersNorth = afterGapMeters, speedMps = 0.0)
        val afterGap =
            (1L..4L).map { offset ->
                pointAt(
                    nanos = (90L + offset) * NANOS_PER_SECOND,
                    metersNorth = afterGapMeters,
                    speedMps = 0.0,
                )
            }

        val cleaned = TraceCleaner.clean(beforeGap + gapPoint + afterGap)
        val state = MovingTimeEstimator.estimate(cleaned.points)

        assertEquals(2, cleaned.segments.size)
        assertEquals(29.0, movingSeconds(state), 0.0)
    }

    private companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BASE_LAT = -33.4489
        private const val BASE_LON = -70.6693
        private const val METERS_PER_LAT_DEGREE = 111_194.9
    }
}
