package com.buzzbike.app.domain.ride

import com.buzzbike.app.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [TraceCleaner] (ADR-005 §2).
 *
 * The helper builds points on a north-south line where one `1e-5` degree lat
 * step is roughly 1.11 m, so implied speeds are easy to reason about.
 * No Android dependencies.
 */
class TraceCleanerTest {
    private fun point(
        seconds: Long,
        latOffsetSteps: Int = 0,
        accuracyH: Double = 5.0,
        speedMps: Double = 5.0,
    ): TrackPoint =
        TrackPoint(
            timestampMonotonicNanos = seconds * NANOS_PER_SECOND,
            lat = BASE_LAT + latOffsetSteps * LAT_STEP_DEGREES,
            lon = BASE_LON,
            accuracyH = accuracyH,
            speedMps = speedMps,
        )

    @Test
    fun given_unorderedPoints_when_cleaned_then_sortedByMonotonicTime() {
        val cleaned =
            TraceCleaner.clean(
                listOf(
                    point(seconds = 3, latOffsetSteps = 3),
                    point(seconds = 1, latOffsetSteps = 1),
                    point(seconds = 2, latOffsetSteps = 2),
                ),
            )

        val seconds = cleaned.points.map { it.timestampMonotonicNanos / NANOS_PER_SECOND }
        assertEquals(listOf(1L, 2L, 3L), seconds)
        assertEquals(0, cleaned.rejectedCount)
        assertEquals(1, cleaned.segments.size)
        assertFalse(cleaned.distanceIsLowerBound)
    }

    @Test
    fun given_accuracyAbove30_when_cleaned_then_rejected() {
        val cleaned =
            TraceCleaner.clean(
                listOf(
                    point(seconds = 0, latOffsetSteps = 0),
                    point(seconds = 1, latOffsetSteps = 1, accuracyH = 31.0),
                ),
            )

        assertEquals(1, cleaned.rejectedCount)
        assertEquals(1, cleaned.points.size)
        assertEquals(0L, cleaned.points.single().timestampMonotonicNanos / NANOS_PER_SECOND)
    }

    @Test
    fun given_teleport_when_cleaned_then_pointRejected() {
        // 30 steps ~= 33.3 m in 1 s -> ~33 m/s, above TELEPORT_SPEED_MPS (25).
        val cleaned =
            TraceCleaner.clean(
                listOf(
                    point(seconds = 0, latOffsetSteps = 0),
                    point(seconds = 1, latOffsetSteps = 30),
                ),
            )

        assertEquals(1, cleaned.rejectedCount)
        assertEquals(1, cleaned.points.size)
        assertEquals(1, cleaned.segments.size)
    }

    @Test
    fun given_gapOver60s_when_cleaned_then_twoSegmentsAndLowerBound() {
        val cleaned =
            TraceCleaner.clean(
                listOf(
                    point(seconds = 0, latOffsetSteps = 0),
                    point(seconds = 61, latOffsetSteps = 61),
                ),
            )

        assertEquals(2, cleaned.points.size)
        assertEquals(2, cleaned.segments.size)
        assertEquals(1, cleaned.gapCount)
        assertTrue(cleaned.distanceIsLowerBound)
    }

    @Test
    fun given_gap10s_when_cleaned_then_gapCountedAndLowerBound() {
        val cleaned =
            TraceCleaner.clean(
                listOf(
                    point(seconds = 0, latOffsetSteps = 0),
                    point(seconds = 10, latOffsetSteps = 10),
                ),
            )

        assertEquals(1, cleaned.segments.size)
        assertEquals(1, cleaned.gapCount)
        assertTrue(cleaned.distanceIsLowerBound)
    }

    @Test
    fun given_gap3s_when_cleaned_then_singleSegmentAndNotLowerBound() {
        val cleaned =
            TraceCleaner.clean(
                listOf(
                    point(seconds = 0, latOffsetSteps = 0),
                    point(seconds = 3, latOffsetSteps = 3),
                ),
            )

        assertEquals(1, cleaned.segments.size)
        assertEquals(0, cleaned.gapCount)
        assertFalse(cleaned.distanceIsLowerBound)
    }

    @Test
    fun given_accuracyRejected_when_cleaned_then_distanceIsLowerBound() {
        val cleaned =
            TraceCleaner.clean(
                listOf(
                    point(seconds = 0, latOffsetSteps = 0),
                    point(seconds = 1, latOffsetSteps = 1, accuracyH = 31.0),
                ),
            )

        assertEquals(1, cleaned.rejectedCount)
        assertTrue(cleaned.distanceIsLowerBound)
    }

    @Test
    fun given_nonFiniteAccuracyV_when_cleaned_thenSanitizedToNull() {
        val cleaned =
            TraceCleaner.clean(
                listOf(point(seconds = 0, latOffsetSteps = 0).copy(accuracyV = Double.NaN)),
            )

        assertEquals(1, cleaned.points.size)
        assertNull(cleaned.points.single().accuracyV)
    }

    private companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BASE_LAT = -33.4489
        private const val BASE_LON = -70.6693
        private const val LAT_STEP_DEGREES = 1e-5
    }
}
