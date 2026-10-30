package com.buzzbike.app.domain.ride

import com.buzzbike.app.domain.geo.GeodesicDistance
import com.buzzbike.app.domain.geo.HaversineDistance
import com.buzzbike.app.domain.model.TrackPoint

/**
 * Half-open moving interval `[startNanos, endNanos)` in monotonic time.
 */
data class TimeInterval(
    val startNanos: Long,
    val endNanos: Long,
)

/**
 * Result of moving/stopped segmentation (ADR-005 §4).
 *
 * [movingIntervals] contains the confirmed moving intervals with backdated
 * boundaries. [stopDurationsSeconds] contains the confirmed stop durations in
 * whole seconds, in chronological order.
 *
 * [isMoving] and [stoppedSinceNanos] describe the state at the last sample, so
 * the session layer can derive auto-pause without re-implementing the
 * hysteresis from ADR-005 §4. [stoppedSinceNanos] is the backdated start of the
 * stop currently in progress, and stays `null` while moving or while the stop
 * is still an unconfirmed candidate (< `T_STOP_SECONDS`).
 */
data class MovingState(
    val movingIntervals: List<TimeInterval>,
    val stopDurationsSeconds: List<Long>,
    val isMoving: Boolean,
    val stoppedSinceNanos: Long?,
)

private enum class MotionState { MOVING, STOPPED }

private const val NANOS_PER_SECOND_LONG = 1_000_000_000L
private const val T_STOP_NANOS = RideParameters.T_STOP_SECONDS * NANOS_PER_SECOND_LONG
private const val T_GO_NANOS = RideParameters.T_GO_SECONDS * NANOS_PER_SECOND_LONG
private const val STOP_DISPLACEMENT_WINDOW_NANOS =
    RideParameters.STOP_DISPLACEMENT_WINDOW_SECONDS * NANOS_PER_SECOND_LONG
private const val RESUME_OVERRIDE_WINDOW_NANOS =
    RideParameters.RESUME_OVERRIDE_WINDOW_SECONDS * NANOS_PER_SECOND_LONG
private const val GAP_INTERPOLATE_MAX_NANOS =
    RideParameters.GAP_INTERPOLATE_MAX_SECONDS * NANOS_PER_SECOND_LONG
private const val GAP_CUT_NANOS = RideParameters.GAP_CUT_SECONDS * NANOS_PER_SECOND_LONG

/**
 * Moving/stopped estimator from ADR-005 §4.
 *
 * Starts `STOPPED`, applies hysteresis (`V_GO > V_STOP`), confirms transitions
 * after `T_STOP` / `T_GO` seconds, backdates them to the first sample of the
 * candidate window, and applies the displacement veto plus the resume
 * displacement override. State is kept in monotonic time only.
 *
 * The accelerometer veto from ADR-005 §4 is not applied because
 * [TrackPoint] carries no accelerometer aggregate; the displacement veto and
 * the resume override are the available signals.
 *
 * Gaps > 5 s do not drive state transitions; state is held and pending
 * candidates are cleared. Missing or unusable speed is treated the same way
 * ("no evidence", not zero). While `MOVING`, the open interval is split around
 * a 5–60 s gap so gap time counts as stopped (`elapsed - moving`), not as
 * moving. A gap > `GAP_CUT_SECONDS` cuts the segment: the moving interval is
 * closed and the new segment starts `STOPPED`, without counting the gap as a
 * confirmed stop.
 *
 * Pure domain code: no `android.*` imports; [GeodesicDistance] is injected for
 * veto/override displacement.
 */
object MovingTimeEstimator {
    fun estimate(
        points: List<TrackPoint>,
        distance: GeodesicDistance = HaversineDistance,
    ): MovingState {
        val sorted = points.sortedBy { it.timestampMonotonicNanos }
        if (sorted.isEmpty()) {
            return MovingState(
                movingIntervals = emptyList(),
                stopDurationsSeconds = emptyList(),
                isMoving = false,
                stoppedSinceNanos = null,
            )
        }

        val accumulator = MovingTimeAccumulator(distance)
        for (index in sorted.indices) {
            val gapNanos =
                if (index > 0) {
                    sorted[index].timestampMonotonicNanos - sorted[index - 1].timestampMonotonicNanos
                } else {
                    0L
                }
            if (gapNanos > GAP_CUT_NANOS) {
                accumulator.onSegmentBreak(sorted, index)
            } else if (gapNanos > GAP_INTERPOLATE_MAX_NANOS) {
                accumulator.onGap(sorted, index)
            } else {
                val speed = SpeedResolver.resolve(sorted[index])
                if (speed == null) {
                    accumulator.onGap(sorted, index)
                } else {
                    accumulator.onSample(sorted, index, speed)
                }
            }
        }
        return accumulator.finish(sorted.last().timestampMonotonicNanos)
    }
}

private class MovingTimeAccumulator(
    private val distance: GeodesicDistance,
) {
    private val intervals = mutableListOf<TimeInterval>()
    private val stops = mutableListOf<Long>()
    private var state = MotionState.STOPPED
    private var movingStartNanos = 0L
    private var stopStartNanos: Long? = null
    private var stopCandidateStartNanos: Long? = null
    private var resumeCandidateStartNanos: Long? = null

    fun onSample(
        points: List<TrackPoint>,
        index: Int,
        speed: Double,
    ) {
        if (state == MotionState.MOVING) {
            if (speed > RideParameters.V_GO_MPS) {
                stopCandidateStartNanos = null
            } else if (speed < RideParameters.V_STOP_MPS) {
                updateStopCandidate(points, index)
            }
        } else {
            updateStopped(points, index, speed)
        }
    }

    fun onGap(
        points: List<TrackPoint>,
        index: Int,
    ) {
        // A gap is always relative to a previous point; index 0 has none.
        if (index <= 0) return
        stopCandidateStartNanos = null
        resumeCandidateStartNanos = null
        if (state == MotionState.MOVING) {
            val gapStartNanos = points[index - 1].timestampMonotonicNanos
            if (gapStartNanos > movingStartNanos) {
                intervals.add(TimeInterval(movingStartNanos, gapStartNanos))
            }
            movingStartNanos = points[index].timestampMonotonicNanos
        }
    }

    fun onSegmentBreak(
        points: List<TrackPoint>,
        index: Int,
    ) {
        val gapEndNanos = points[index - 1].timestampMonotonicNanos
        when (state) {
            MotionState.MOVING -> {
                if (gapEndNanos > movingStartNanos) {
                    intervals.add(TimeInterval(movingStartNanos, gapEndNanos))
                }
            }
            MotionState.STOPPED -> {
                val openStopStart = stopStartNanos
                if (openStopStart != null && gapEndNanos > openStopStart) {
                    stops.add((gapEndNanos - openStopStart) / NANOS_PER_SECOND_LONG)
                }
            }
        }
        stopStartNanos = null
        stopCandidateStartNanos = null
        resumeCandidateStartNanos = null
        state = MotionState.STOPPED
    }

    fun finish(lastNanos: Long): MovingState {
        when (state) {
            MotionState.MOVING -> {
                if (lastNanos > movingStartNanos) {
                    intervals.add(TimeInterval(movingStartNanos, lastNanos))
                }
            }
            MotionState.STOPPED -> {
                val openStopStart = stopStartNanos
                if (openStopStart != null && lastNanos > openStopStart) {
                    stops.add((lastNanos - openStopStart) / NANOS_PER_SECOND_LONG)
                }
            }
        }
        return MovingState(
            movingIntervals = intervals.toList(),
            stopDurationsSeconds = stops.toList(),
            isMoving = state == MotionState.MOVING,
            stoppedSinceNanos = if (state == MotionState.STOPPED) stopStartNanos else null,
        )
    }

    private fun updateStopCandidate(
        points: List<TrackPoint>,
        index: Int,
    ) {
        val candidateStart = stopCandidateStartNanos
        val nowNanos = points[index].timestampMonotonicNanos
        when {
            candidateStart == null -> stopCandidateStartNanos = nowNanos
            isDisplacementVetoed(points, index, candidateStart) -> stopCandidateStartNanos = null
            nowNanos - candidateStart >= T_STOP_NANOS -> {
                if (candidateStart > movingStartNanos) {
                    intervals.add(TimeInterval(movingStartNanos, candidateStart))
                }
                state = MotionState.STOPPED
                stopStartNanos = candidateStart
                resumeCandidateStartNanos = null
                stopCandidateStartNanos = null
            }
        }
    }

    private fun updateStopped(
        points: List<TrackPoint>,
        index: Int,
        speed: Double,
    ) {
        val nowNanos = points[index].timestampMonotonicNanos
        if (speed > RideParameters.V_GO_MPS) {
            val candidateStart = resumeCandidateStartNanos ?: nowNanos
            resumeCandidateStartNanos = candidateStart
            if (nowNanos - candidateStart >= T_GO_NANOS) {
                startMoving(candidateStart)
            }
        } else {
            resumeCandidateStartNanos = null
        }
        if (state == MotionState.STOPPED && resumeCandidateStartNanos == null) {
            val overrideStart = overrideStartNanos(points, index)
            if (overrideStart != null) {
                startMoving(overrideStart)
            }
        }
    }

    private fun isDisplacementVetoed(
        points: List<TrackPoint>,
        index: Int,
        candidateStart: Long,
    ): Boolean {
        val nowNanos = points[index].timestampMonotonicNanos
        val cutoff = maxOf(candidateStart, nowNanos - STOP_DISPLACEMENT_WINDOW_NANOS)
        var referenceIndex = index
        while (referenceIndex > 0 && points[referenceIndex - 1].timestampMonotonicNanos >= cutoff) {
            referenceIndex--
        }
        val reference = points[referenceIndex]
        val current = points[index]
        val referenceAccuracy = reference.accuracyH
        val currentAccuracy = current.accuracyH
        return referenceAccuracy != null &&
            currentAccuracy != null &&
            referenceAccuracy <= RideParameters.LOW_QUALITY_ACCURACY_H_M &&
            currentAccuracy <= RideParameters.LOW_QUALITY_ACCURACY_H_M &&
            distance.meters(reference, current) >= RideParameters.STOP_DISPLACEMENT_VETO_M
    }

    private fun overrideStartNanos(
        points: List<TrackPoint>,
        index: Int,
    ): Long? {
        val cutoff = points[index].timestampMonotonicNanos - RESUME_OVERRIDE_WINDOW_NANOS
        var cursor = index
        while (
            cursor > 0 &&
            points[cursor].timestampMonotonicNanos - points[cursor - 1].timestampMonotonicNanos <=
            GAP_INTERPOLATE_MAX_NANOS &&
            points[cursor - 1].timestampMonotonicNanos >= cutoff
        ) {
            cursor--
        }
        val reference = points[cursor]
        if (reference.timestampMonotonicNanos > cutoff) {
            return null
        }
        val current = points[index]
        val referenceAccuracy = reference.accuracyH
        val currentAccuracy = current.accuracyH
        val moved =
            referenceAccuracy != null &&
                currentAccuracy != null &&
                referenceAccuracy <= RideParameters.LOW_QUALITY_ACCURACY_H_M &&
                currentAccuracy <= RideParameters.LOW_QUALITY_ACCURACY_H_M &&
                distance.meters(reference, current) >= RideParameters.RESUME_DISPLACEMENT_OVERRIDE_M
        return if (moved) reference.timestampMonotonicNanos else null
    }

    private fun startMoving(startNanos: Long) {
        val stopStart = stopStartNanos
        if (stopStart != null) {
            if (startNanos > stopStart) {
                stops.add((startNanos - stopStart) / NANOS_PER_SECOND_LONG)
            }
            stopStartNanos = null
        }
        state = MotionState.MOVING
        movingStartNanos = startNanos
        stopCandidateStartNanos = null
        resumeCandidateStartNanos = null
    }
}
