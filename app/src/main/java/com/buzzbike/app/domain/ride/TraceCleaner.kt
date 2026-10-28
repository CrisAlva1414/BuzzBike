package com.buzzbike.app.domain.ride

import com.buzzbike.app.domain.geo.GeodesicDistance
import com.buzzbike.app.domain.geo.HaversineDistance
import com.buzzbike.app.domain.model.TrackPoint

/**
 * Result of cleaning a raw trace according to ADR-005 §2.
 *
 * [segments] are continuous runs of accepted points split at gaps larger than
 * [RideParameters.GAP_CUT_SECONDS]. [distanceIsLowerBound] is `true` when at
 * least one gap larger than [RideParameters.GAP_INTERPOLATE_MAX_SECONDS] was
 * found or when any point was rejected, so any distance computed from this
 * trace is a lower bound.
 */
data class CleanedTrace(
    val points: List<TrackPoint>,
    val segments: List<List<TrackPoint>>,
    val rejectedCount: Int,
    val gapCount: Int,
    val distanceIsLowerBound: Boolean,
)

/**
 * Deterministic trace cleanup for a ride (ADR-005 §2).
 *
 * Steps: sort by [TrackPoint.timestampMonotonicNanos], drop exact timestamp
 * duplicates, reject points with invalid coordinates or accuracy (non-finite,
 * non-positive, or above `MAX_ACCURACY_H_M`), and reject implausible jumps
 * faster than `TELEPORT_SPEED_MPS` between consecutive accepted points.
 * Non-finite optional measurements are sanitized to `null` while keeping the
 * point. Segments are cut at gaps larger than `GAP_CUT_SECONDS`; gaps above
 * `GAP_INTERPOLATE_MAX_SECONDS` or any rejected point also mark the distance as
 * a lower bound.
 *
 * **No interpolation in this slice.** No synthetic points are inserted for
 * gaps, and no altitude/elevation values are interpolated. Elevation handling
 * belongs to ADR-006 and will be layered on top of this cleaner.
 *
 * Pure domain code: no `android.*` imports. The distance algorithm is injected
 * through [GeodesicDistance] so tests stay on the JVM.
 */
object TraceCleaner {
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    private const val MIN_LATITUDE_DEGREES = -90.0
    private const val MAX_LATITUDE_DEGREES = 90.0
    private const val MIN_LONGITUDE_DEGREES = -180.0
    private const val MAX_LONGITUDE_DEGREES = 180.0

    private val latitudeRange = MIN_LATITUDE_DEGREES..MAX_LATITUDE_DEGREES
    private val longitudeRange = MIN_LONGITUDE_DEGREES..MAX_LONGITUDE_DEGREES

    fun clean(
        points: List<TrackPoint>,
        distance: GeodesicDistance = HaversineDistance,
    ): CleanedTrace {
        val sorted =
            points
                .sortedBy { it.timestampMonotonicNanos }
                .distinctBy { it.timestampMonotonicNanos }

        val accepted = mutableListOf<TrackPoint>()
        val segments = mutableListOf<MutableList<TrackPoint>>()
        var currentSegment = mutableListOf<TrackPoint>()
        var rejectedCount = 0
        var gapCount = 0

        for (point in sorted) {
            val previous = accepted.lastOrNull()
            if (isRejected(previous, point, distance)) {
                rejectedCount++
                continue
            }
            val sanitized = sanitize(point)
            accepted.add(sanitized)
            if (previous == null) {
                segments.add(currentSegment)
            } else {
                val gapNanos = sanitized.timestampMonotonicNanos - previous.timestampMonotonicNanos
                val gapSeconds = gapNanos / NANOS_PER_SECOND
                if (gapSeconds > RideParameters.GAP_CUT_SECONDS) {
                    currentSegment = mutableListOf()
                    segments.add(currentSegment)
                }
                if (gapSeconds > RideParameters.GAP_INTERPOLATE_MAX_SECONDS) {
                    gapCount++
                }
            }
            currentSegment.add(sanitized)
        }

        return CleanedTrace(
            points = accepted,
            segments = segments.map { it.toList() },
            rejectedCount = rejectedCount,
            gapCount = gapCount,
            distanceIsLowerBound = gapCount > 0 || rejectedCount > 0,
        )
    }

    private fun isRejected(
        previous: TrackPoint?,
        point: TrackPoint,
        distance: GeodesicDistance,
    ): Boolean =
        !isValidPosition(point) ||
            !isUsableAccuracy(point.accuracyH) ||
            previous != null &&
            isTeleport(previous, point, distance)

    private fun isValidPosition(point: TrackPoint): Boolean = isValidLatitude(point.lat) && isValidLongitude(point.lon)

    private fun isValidLatitude(lat: Double): Boolean = lat.isFinite() && lat in latitudeRange

    private fun isValidLongitude(lon: Double): Boolean = lon.isFinite() && lon in longitudeRange

    private fun isUsableAccuracy(accuracyH: Double): Boolean =
        accuracyH.isFinite() &&
            accuracyH > 0.0 &&
            accuracyH <= RideParameters.MAX_ACCURACY_H_M

    private fun isTeleport(
        previous: TrackPoint,
        point: TrackPoint,
        distance: GeodesicDistance,
    ): Boolean {
        val dtNanos = point.timestampMonotonicNanos - previous.timestampMonotonicNanos
        return dtNanos <= 0L ||
            distance.meters(previous, point) / (dtNanos / NANOS_PER_SECOND) >
            RideParameters.TELEPORT_SPEED_MPS
    }

    private fun sanitize(point: TrackPoint): TrackPoint =
        point.copy(
            altitudeEllipsoidM = point.altitudeEllipsoidM.finiteOrNull(),
            altitudeMslM = point.altitudeMslM.finiteOrNull(),
            accuracyV = point.accuracyV.finiteOrNull(),
            speedMps = point.speedMps?.takeIf { it.isFinite() && it >= 0.0 },
            speedAccuracyMps = point.speedAccuracyMps.finiteOrNull(),
            pressureHpa = point.pressureHpa.finiteOrNull(),
            accelNormStd = point.accelNormStd.finiteOrNull(),
        )

    private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }
}
