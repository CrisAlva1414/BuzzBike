package com.buzzbike.app.domain.ride

import com.buzzbike.app.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [MovingTimeEstimator] (ADR-005 §4).
 *
 * [trace] builds points on a north-south line. Positions advance by
 * `positionSpeedsMps[index] * dtSeconds`, while the reported `speedMps` can be
 * overridden independently to exercise the displacement override.
 * No Android dependencies.
 */
class MovingTimeEstimatorTest {
    private fun trace(
        speedsMps: List<Double>,
        dtSeconds: Long = 1,
        positionSpeedsMps: List<Double> = speedsMps,
        gapAfterIndex: Int? = null,
        gapSeconds: Long = 0,
    ): List<TrackPoint> {
        var meters = 0.0
        var elapsedNanos = 0L
        return speedsMps.indices.map { index ->
            val stepSeconds =
                if (gapAfterIndex != null && index == gapAfterIndex + 1) {
                    gapSeconds
                } else {
                    dtSeconds
                }
            if (index > 0) {
                meters += positionSpeedsMps[index] * stepSeconds
                elapsedNanos += stepSeconds * NANOS_PER_SECOND_LONG
            }
            TrackPoint(
                timestampMonotonicNanos = elapsedNanos,
                lat = BASE_LAT + meters / METERS_PER_LAT_DEGREE,
                lon = BASE_LON,
                accuracyH = 5.0,
                speedMps = speedsMps[index],
            )
        }
    }

    private fun movingSeconds(state: MovingState): Double =
        state.movingIntervals.sumOf { (it.endNanos - it.startNanos) / NANOS_PER_SECOND }

    @Test
    fun given_trafficLightStop30s_when_estimated_then_movingExcludesStop() {
        val speeds = List(60) { 5.555 } + List(30) { 0.0 } + List(60) { 5.555 }

        val state = MovingTimeEstimator.estimate(trace(speeds))

        assertEquals(120.0, movingSeconds(state), 1.0)
        assertEquals(listOf(30L), state.stopDurationsSeconds)
    }

    @Test
    fun given_slowClimb5kmh_when_estimated_then_moving() {
        val state = MovingTimeEstimator.estimate(trace(List(300) { 1.389 }))

        assertEquals(300.0, movingSeconds(state), 1.0)
        assertTrue(state.stopDurationsSeconds.isEmpty())
    }

    @Test
    fun given_startAlreadyMoving_when_estimated_then_movingFromStart() {
        val state = MovingTimeEstimator.estimate(trace(List(10) { 5.0 }))

        assertEquals(10.0, movingSeconds(state), 1.0)
        assertEquals(0L, state.movingIntervals.single().startNanos)
    }

    @Test
    fun given_shortStop5s_when_estimated_then_notAStop() {
        val speeds = List(20) { 5.0 } + List(5) { 0.0 } + List(20) { 5.0 }

        val state = MovingTimeEstimator.estimate(trace(speeds))

        assertEquals(45.0, movingSeconds(state), 1.0)
        assertTrue(state.stopDurationsSeconds.isEmpty())
    }

    @Test
    fun given_speedZeroButDisplacement_when_estimated_then_resumeOverride() {
        val state =
            MovingTimeEstimator.estimate(
                trace(
                    speedsMps = List(15) { 0.0 },
                    positionSpeedsMps = List(15) { 1.2 },
                ),
            )

        assertEquals(1, state.movingIntervals.size)
        assertEquals(0L, state.movingIntervals.single().startNanos)
        assertEquals(15.0, movingSeconds(state), 1.0)
        assertTrue(state.stopDurationsSeconds.isEmpty())
    }

    @Test
    fun given_tunnelGap45s_when_estimated_then_movingNotCut() {
        val speeds = List(60) { 5.555 } + List(60) { 5.555 }

        val state =
            MovingTimeEstimator.estimate(
                trace(
                    speedsMps = speeds,
                    gapAfterIndex = 59,
                    gapSeconds = 45,
                ),
            )

        assertEquals(120.0, movingSeconds(state), 2.0)
        assertTrue(state.stopDurationsSeconds.isEmpty())
    }

    @Test
    fun given_movingTrace_when_estimated_thenStoppedSinceIsNull() {
        val state = MovingTimeEstimator.estimate(trace(List(20) { 5.0 }))

        assertTrue(state.isMoving)
        assertNull(state.stoppedSinceNanos)
    }

    @Test
    fun given_confirmedStop_when_estimated_thenStoppedSinceIsExposed() {
        val speeds = List(20) { 5.0 } + List(15) { 0.0 }

        val state = MovingTimeEstimator.estimate(trace(speeds))

        assertFalse(state.isMoving)
        assertNotNull(state.stoppedSinceNanos)
        assertEquals(1, state.stopDurationsSeconds.size)
    }

    @Test
    fun given_stopCandidateShorterThanTStop_when_estimated_thenStoppedSinceIsNull() {
        // Stop candidate confirmed only after T_STOP_SECONDS (10 s from ADR-005).
        val speeds = List(20) { 5.0 } + List(5) { 0.0 }

        val state = MovingTimeEstimator.estimate(trace(speeds))

        assertTrue(state.isMoving)
        assertNull(state.stoppedSinceNanos)
    }

    @Test
    fun given_stationaryFromStart_when_estimated_thenNoAutoPauseWindow() {
        // A ride that never confirmed movement must not auto-pause.
        val state = MovingTimeEstimator.estimate(trace(List(15) { 0.0 }))

        assertFalse(state.isMoving)
        assertNull(state.stoppedSinceNanos)
    }

    @Test
    fun given_segmentBreakGap_when_estimated_thenGapDoesNotConfirmAStop() {
        // Gaps > GAP_CUT_SECONDS cut the segment; ADR-005 says that is not a
        // confirmed stop, so auto-pause must not be inferred from the gap.
        val speeds = List(20) { 5.0 } + List(2) { 0.0 }

        val state =
            MovingTimeEstimator.estimate(
                trace(
                    speedsMps = speeds,
                    gapAfterIndex = 19,
                    gapSeconds = 70,
                ),
            )

        assertFalse(state.isMoving)
        assertNull(state.stoppedSinceNanos)
    }

    @Test
    fun given_stopThenResume_when_estimated_thenStoppedSinceIsCleared() {
        val speeds = List(20) { 5.0 } + List(15) { 0.0 } + List(20) { 5.0 }

        val state = MovingTimeEstimator.estimate(trace(speeds))

        assertTrue(state.isMoving)
        assertNull(state.stoppedSinceNanos)
        assertEquals(1, state.stopDurationsSeconds.size)
    }

    @Test
    fun given_gapWhileStopped_when_estimated_then_stateStaysStopped() {
        val speeds = List(30) { 0.0 } + List(30) { 0.0 }

        val state =
            MovingTimeEstimator.estimate(
                trace(
                    speedsMps = speeds,
                    gapAfterIndex = 29,
                    gapSeconds = 30,
                ),
            )

        assertEquals(0.0, movingSeconds(state), 0.0)
        assertTrue(state.stopDurationsSeconds.isEmpty())
    }

    @Test
    fun given_stopBeforeSegmentBreak_when_estimated_thenStopIsClosed() {
        val speeds = List(10) { 5.0 } + List(15) { 0.0 } + List(1) { 0.0 }

        val state =
            MovingTimeEstimator.estimate(
                trace(
                    speedsMps = speeds,
                    gapAfterIndex = 24,
                    gapSeconds = 76,
                ),
            )

        assertEquals(1, state.stopDurationsSeconds.size)
        assertTrue(state.stopDurationsSeconds.single() in 14L..15L)
    }

    private companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_SECOND_LONG = 1_000_000_000L
        private const val BASE_LAT = -33.4489
        private const val BASE_LON = -70.6693
        private const val METERS_PER_LAT_DEGREE = 111_194.9
    }
}
