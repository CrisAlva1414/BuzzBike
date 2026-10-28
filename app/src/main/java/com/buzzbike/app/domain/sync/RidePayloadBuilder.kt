package com.buzzbike.app.domain.sync

import com.buzzbike.app.domain.model.TrackPoint
import kotlin.math.round

/**
 * Builds the data-minimized sync payload for a ride (ADR-003).
 *
 * The raw trace is never sent. Points are validated, sorted by monotonic time
 * and decimated to at most one sample every [DEFAULT_DECIMATION_SECONDS]
 * seconds, capped at [DEFAULT_MAX_WAYPOINTS] entries. The first and last valid
 * points are always preserved so the receiver can draw the full ride extent.
 *
 * All non-finite metrics are sanitized before they reach [RideSyncPayload]:
 * doubles become `0.0` or `null` (for nullable fields) and never `NaN` or
 * `Infinity`.
 */
object RidePayloadBuilder {
    fun build(
        input: RideSyncInput,
        decimationSeconds: Long = DEFAULT_DECIMATION_SECONDS,
        maxWaypoints: Int = DEFAULT_MAX_WAYPOINTS,
    ): RideSyncPayload {
        val sortedPoints =
            input.points
                .filter(::isValidPoint)
                .sortedBy { it.timestampMonotonicNanos }
        val selected = decimate(sortedPoints, decimationSeconds, maxWaypoints)

        return RideSyncPayload(
            rideId = input.rideId,
            startTimeMillis = input.startTimeMillis,
            endTimeMillis = input.endTimeMillis,
            distanceM = finiteOrZero(input.descriptors.distanceM),
            movingTimeSeconds = input.descriptors.movingSeconds.coerceAtLeast(0L),
            stoppedTimeSeconds = input.descriptors.stoppedSeconds.coerceAtLeast(0L),
            elevationGainM = finiteOrZero(input.elevationGainM),
            elevationLossM = finiteOrZero(input.elevationLossM),
            calories = buildCalories(input),
            avgSpeedMps = finiteOrZero(input.descriptors.avgSpeedMps),
            maxSpeedMps = finiteOrZero(input.maxSpeedMps),
            waypoints =
                toWaypoints(
                    points = selected,
                    startTimeMillis = input.startTimeMillis,
                    referenceNanos = selected.firstOrNull()?.timestampMonotonicNanos,
                ),
        )
    }

    private fun isValidPoint(point: TrackPoint): Boolean =
        point.lat.isFinite() &&
            point.lon.isFinite() &&
            point.lat in MIN_LAT..MAX_LAT &&
            point.lon in MIN_LON..MAX_LON

    private fun decimate(
        points: List<TrackPoint>,
        decimationSeconds: Long,
        maxWaypoints: Int,
    ): List<TrackPoint> {
        if (points.isEmpty()) return emptyList()

        val first = points.first()
        val kept = mutableListOf(first)
        if (points.size > 1) {
            val intervalNanos = decimationSeconds.coerceAtLeast(0L) * NANOS_PER_SECOND
            var lastKeptNanos = first.timestampMonotonicNanos
            for (index in 1 until points.lastIndex) {
                val point = points[index]
                if (point.timestampMonotonicNanos - lastKeptNanos >= intervalNanos) {
                    kept.add(point)
                    lastKeptNanos = point.timestampMonotonicNanos
                }
            }
            kept.add(points.last())
        }
        return limitWaypoints(kept, maxWaypoints)
    }

    private fun limitWaypoints(
        points: List<TrackPoint>,
        maxWaypoints: Int,
    ): List<TrackPoint> =
        when {
            maxWaypoints <= 0 -> emptyList()
            points.size <= maxWaypoints -> points
            maxWaypoints == 1 -> listOf(points.first())
            else -> sampleUniformly(points, maxWaypoints)
        }

    /**
     * Keeps first and last and samples the middle uniformly.
     *
     * [points] is guaranteed to be larger than [maxWaypoints] here, so the
     * number of middle slots never exceeds the middle size.
     */
    private fun sampleUniformly(
        points: List<TrackPoint>,
        maxWaypoints: Int,
    ): List<TrackPoint> {
        val middle = points.subList(1, points.lastIndex)
        val slots = maxWaypoints - 2
        val sampled = ArrayList<TrackPoint>(maxWaypoints)
        sampled.add(points.first())
        for (slot in 0 until slots) {
            val index = ((2L * slot + 1) * middle.size / (2L * slots)).toInt()
            sampled.add(middle[index])
        }
        sampled.add(points.last())
        return sampled
    }

    /**
     * Reconstructs epoch millis from monotonic nanos anchored at the ride start.
     *
     * [TrackPoint] has no epoch timestamp of its own, so the resulting
     * [Waypoint.timestampMillis] is relative to [startTimeMillis], not an
     * absolute measurement. The first valid point maps exactly to
     * [startTimeMillis].
     */
    private fun toWaypoints(
        points: List<TrackPoint>,
        startTimeMillis: Long,
        referenceNanos: Long?,
    ): List<Waypoint> {
        if (referenceNanos == null) return emptyList()

        return points.map { point ->
            Waypoint(
                timestampMillis = startTimeMillis + (point.timestampMonotonicNanos - referenceNanos) / NANOS_PER_MILLI,
                lat = roundCoordinate(point.lat),
                lon = roundCoordinate(point.lon),
                elevationM = resolveElevation(point),
            )
        }
    }

    private fun resolveElevation(point: TrackPoint): Double? =
        point.altitudeMslM?.takeIf { it.isFinite() }
            ?: point.altitudeEllipsoidM?.takeIf { it.isFinite() }

    private fun roundCoordinate(value: Double): Double = round(value * COORDINATE_SCALE) / COORDINATE_SCALE

    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0

    private const val DEFAULT_DECIMATION_SECONDS = 10L
    private const val DEFAULT_MAX_WAYPOINTS = 2000
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val COORDINATE_SCALE = 1_000_000.0
    private const val MIN_LAT = -90.0
    private const val MAX_LAT = 90.0
    private const val MIN_LON = -180.0
    private const val MAX_LON = 180.0
}

/**
 * Builds the nested calorie band only when all four components are valid.
 *
 * A missing model or interval means the caller cannot express the uncertainty
 * (ADR-001), so the whole object is dropped instead of forwarding a bare point
 * estimate. Non-finite doubles are sanitized to "absent", and an inverted band
 * is rejected.
 */
private fun buildCalories(input: RideSyncInput): CaloriesPayload? {
    val estimate = input.caloriesKcal?.takeIf(::isFiniteNonNegative)
    val low = input.caloriesLowKcal?.takeIf(::isFiniteNonNegative)
    val high = input.caloriesHighKcal?.takeIf(::isFiniteNonNegative)
    val model = input.calorieModel?.takeIf { it.isNotBlank() }
    val band =
        if (estimate == null || low == null || high == null) {
            null
        } else if (model == null) {
            null
        } else {
            CaloriesPayload(
                estimateKcal = estimate,
                lowKcal = low,
                highKcal = high,
                model = model,
            )
        }
    return band?.takeIf { it.lowKcal <= it.estimateKcal && it.estimateKcal <= it.highKcal }
}

private fun isFiniteNonNegative(value: Double): Boolean = value.isFinite() && value >= MIN_CALORIES_KCAL

private const val MIN_CALORIES_KCAL = 0.0
