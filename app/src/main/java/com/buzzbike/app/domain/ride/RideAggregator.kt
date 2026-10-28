package com.buzzbike.app.domain.ride

import com.buzzbike.app.domain.geo.GeodesicDistance
import com.buzzbike.app.domain.geo.HaversineDistance
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import kotlin.math.ceil

private const val NANOS_PER_SECOND_LONG = 1_000_000_000L
private const val GAP_INTERPOLATE_MAX_NANOS =
    RideParameters.GAP_INTERPOLATE_MAX_SECONDS * NANOS_PER_SECOND_LONG

/**
 * Half-width of the centered median window used to smooth sample speed.
 *
 * **Engineering decision** (ADR-005 §6): a 5 s centered window (±2.5 s), with a
 * minimum of 3 samples; otherwise the individual sample is kept.
 */
private const val SPEED_SMOOTH_HALF_WINDOW_NANOS = 2_500_000_000L

/**
 * Percentile used for peak speed, expressed as a fraction.
 *
 * **Engineering decision** (ADR-005 §6): p95 is robust to single spikes, so
 * `maxSpeedMps` is not the raw maximum.
 */
private const val MAX_SPEED_PERCENTILE = 0.95

/**
 * Minimum samples required inside the centered median window.
 *
 * **Engineering decision** (ADR-005 §6): below 3 samples the median is not
 * meaningful, so the individual sample is kept instead.
 */
private const val SPEED_MEDIAN_MIN_SAMPLES = 3

private data class SpeedSample(
    val timestampMonotonicNanos: Long,
    val speedMps: Double,
)

/**
 * Aggregates a [CleanedTrace] into [RideDescriptors] (ADR-005 §5–§6).
 *
 * Distance is accumulated only between consecutive points of the same segment,
 * only for `dt <= GAP_INTERPOLATE_MAX_SECONDS`, and only when the pair's
 * midpoint falls inside a confirmed moving interval. Time split, stop
 * durations and backdating come from [MovingTimeEstimator].
 *
 * Peak speed is a p95 over a 5 s centered median of usable sample speeds,
 * restricted to moving intervals and normal-accuracy points. It is never the
 * raw maximum and never non-finite. Pure domain code: no `android.*` imports.
 */
object RideAggregator {
    fun describe(
        cleaned: CleanedTrace,
        distance: GeodesicDistance = HaversineDistance,
    ): RideDescriptors {
        val points = cleaned.points
        val movingState = MovingTimeEstimator.estimate(points, distance)
        val intervals = movingState.movingIntervals
        val firstNanos = points.firstOrNull()?.timestampMonotonicNanos
        val lastNanos = points.lastOrNull()?.timestampMonotonicNanos
        val elapsedNanos =
            if (firstNanos != null && lastNanos != null) lastNanos - firstNanos else 0L
        val movingNanos =
            if (firstNanos != null && lastNanos != null) {
                movingOverlapNanos(intervals, firstNanos, lastNanos)
            } else {
                0L
            }
        val elapsedSeconds = elapsedNanos / NANOS_PER_SECOND_LONG
        val movingSeconds = movingNanos / NANOS_PER_SECOND_LONG
        val stoppedSeconds = (elapsedSeconds - movingSeconds).coerceAtLeast(0L)
        val distanceM = movingDistanceM(cleaned.segments, intervals, distance)
        val avgMovingSpeedMps =
            if (movingSeconds > 0) distanceM / movingSeconds.toDouble() else 0.0
        val avgSpeedMps =
            if (elapsedSeconds > 0) distanceM / elapsedSeconds.toDouble() else 0.0

        return RideDescriptors(
            distanceM = distanceM,
            elapsedSeconds = elapsedSeconds,
            movingSeconds = movingSeconds,
            stoppedSeconds = stoppedSeconds,
            avgMovingSpeedMps = avgMovingSpeedMps,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = peakSpeedMps(points, intervals),
            stopCount = movingState.stopDurationsSeconds.size,
            stopDurationsSeconds = movingState.stopDurationsSeconds,
            acceptedPoints = points.size,
            rejectedPoints = cleaned.rejectedCount,
            gapCount = cleaned.gapCount,
            distanceIsLowerBound = cleaned.distanceIsLowerBound,
            modelVersion = RideParameters.MODEL_VERSION,
        )
    }

    private fun movingDistanceM(
        segments: List<List<TrackPoint>>,
        intervals: List<TimeInterval>,
        distance: GeodesicDistance,
    ): Double {
        var total = 0.0
        for (segment in segments) {
            for (index in 1 until segment.size) {
                val previous = segment[index - 1]
                val current = segment[index]
                val dtNanos = current.timestampMonotonicNanos - previous.timestampMonotonicNanos
                val eligible =
                    dtNanos > 0L &&
                        dtNanos <= GAP_INTERPOLATE_MAX_NANOS &&
                        isMovingAt(
                            previous.timestampMonotonicNanos +
                                (current.timestampMonotonicNanos - previous.timestampMonotonicNanos) / 2,
                            intervals,
                        )
                if (eligible) {
                    total += distance.meters(previous, current)
                }
            }
        }
        return total
    }

    private fun movingOverlapNanos(
        intervals: List<TimeInterval>,
        firstNanos: Long,
        lastNanos: Long,
    ): Long {
        var total = 0L
        for (interval in intervals) {
            val start = maxOf(interval.startNanos, firstNanos)
            val end = minOf(interval.endNanos, lastNanos)
            if (end > start) {
                total += end - start
            }
        }
        return total
    }

    private fun isMovingAt(
        nanos: Long,
        intervals: List<TimeInterval>,
    ): Boolean = intervals.any { nanos >= it.startNanos && nanos < it.endNanos }

    private fun peakSpeedMps(
        points: List<TrackPoint>,
        intervals: List<TimeInterval>,
    ): Double {
        val samples =
            points.mapNotNull { point ->
                val speed = SpeedResolver.resolve(point)
                if (speed != null && isUsableSpeedSample(point, intervals)) {
                    SpeedSample(point.timestampMonotonicNanos, speed)
                } else {
                    null
                }
            }
        if (samples.isEmpty()) {
            return 0.0
        }
        return percentile95(centeredMedians(samples))
    }

    private fun isUsableSpeedSample(
        point: TrackPoint,
        intervals: List<TimeInterval>,
    ): Boolean {
        val accuracy = point.accuracyH
        return accuracy != null &&
            accuracy <= RideParameters.LOW_QUALITY_ACCURACY_H_M &&
            isMovingAt(point.timestampMonotonicNanos, intervals)
    }

    private fun centeredMedians(samples: List<SpeedSample>): List<Double> {
        val result = mutableListOf<Double>()
        var left = 0
        var right = 0
        for (index in samples.indices) {
            val center = samples[index].timestampMonotonicNanos
            while (
                left < index &&
                samples[left].timestampMonotonicNanos < center - SPEED_SMOOTH_HALF_WINDOW_NANOS
            ) {
                left++
            }
            if (right < index) {
                right = index
            }
            while (
                right + 1 < samples.size &&
                samples[right + 1].timestampMonotonicNanos <= center + SPEED_SMOOTH_HALF_WINDOW_NANOS
            ) {
                right++
            }
            val count = right - left + 1
            val value =
                if (count >= SPEED_MEDIAN_MIN_SAMPLES) {
                    median(samples.subList(left, right + 1))
                } else {
                    samples[index].speedMps
                }
            result.add(value)
        }
        return result
    }

    private fun median(samples: List<SpeedSample>): Double {
        val sorted = samples.map { it.speedMps }.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun percentile95(values: List<Double>): Double {
        if (values.isEmpty()) {
            return 0.0
        }
        val sorted = values.sorted()
        val nearestRank = ceil(MAX_SPEED_PERCENTILE * sorted.size).toInt() - 1
        return sorted[nearestRank.coerceIn(0, sorted.lastIndex)]
    }
}
