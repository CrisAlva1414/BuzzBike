package com.buzzbike.app.domain.elevation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val BASE_CONFIDENCE = 0.5
private const val BAROMETER_CONFIDENCE_BONUS = 0.3
private const val GPS_MSL_CONFIDENCE_BONUS = 0.1
private const val BAROMETER_ANCHOR_PENALTY = 0.2
private const val BAROMETER_UNCERTAINTY_M = 5.0
private const val GPS_UNCERTAINTY_M = 15.0

private data class Anchor(
    val altitudeM: Double,
    val source: ElevationSource,
    val pressureHpa: Double?,
)

private data class ProcessedPoint(
    val timestampNanos: Long,
    val altitude: Double,
    val sample: ElevationSample,
)

private fun emptyDescriptors(
    hasBarometer: Boolean,
    source: ElevationSource,
    anchorSource: ElevationSource,
): ElevationDescriptors =
    ElevationDescriptors(
        gainM = 0.0,
        lossM = 0.0,
        netM = 0.0,
        source = source,
        confidence = 0.0,
        hasBarometer = hasBarometer,
        anchorSource = anchorSource,
    )

private fun buildProcessedPoints(
    samples: List<ElevationSample>,
    altitudeOf: (ElevationSample) -> Double?,
): List<ProcessedPoint> =
    samples.mapNotNull { sample ->
        altitudeOf(sample)?.let { altitude ->
            ProcessedPoint(sample.timestampNanos, altitude, sample)
        }
    }

private fun bestAnchor(samples: List<ElevationSample>): Anchor? {
    val pressure = firstFinitePressure(samples)
    val mslAltitude = averageMslAltitude(samples)
    val ellipsoidAltitude = averageEllipsoidAltitude(samples)
    return when {
        mslAltitude != null -> Anchor(mslAltitude, ElevationSource.GPS_MSL, pressure)
        ellipsoidAltitude != null -> Anchor(ellipsoidAltitude, ElevationSource.GPS_ELLIPSOID, pressure)
        pressure != null -> Anchor(0.0, ElevationSource.BAROMETER, pressure)
        else -> null
    }
}

private fun averageMslAltitude(samples: List<ElevationSample>): Double? {
    val altitudes =
        samples
            .filter { hasUsableMslAltitude(it) }
            .take(ElevationParameters.ANCHOR_AVG_SECONDS.toInt())
            .mapNotNull { it.altitudeGpsMslM }
    return if (altitudes.isEmpty()) null else altitudes.average()
}

private fun averageEllipsoidAltitude(samples: List<ElevationSample>): Double? {
    val altitudes =
        samples
            .filter { it.altitudeGpsEllipsoidM != null && it.altitudeGpsEllipsoidM.isFinite() }
            .take(ElevationParameters.ANCHOR_AVG_SECONDS.toInt())
            .mapNotNull { it.altitudeGpsEllipsoidM }
    return if (altitudes.isEmpty()) null else altitudes.average()
}

private fun hasUsableMslAltitude(sample: ElevationSample): Boolean {
    val altitude = sample.altitudeGpsMslM
    return altitude != null &&
        altitude.isFinite() &&
        isAccuracyAcceptable(sample.verticalAccuracyM)
}

private fun isAccuracyAcceptable(verticalAccuracyM: Double?): Boolean =
    verticalAccuracyM == null ||
        verticalAccuracyM <= ElevationParameters.ANCHOR_MIN_VERTICAL_ACCURACY_M

private fun firstFinitePressure(samples: List<ElevationSample>): Double? =
    samples.firstNotNullOfOrNull { sample ->
        sample.pressureHpa?.takeIf { it.isFinite() }
    }

private fun hysteresisM(hasBarometer: Boolean): Double =
    if (hasBarometer) ElevationParameters.HYSTERESIS_BARO_M else ElevationParameters.HYSTERESIS_GPS_M

private fun gpsAltitude(sample: ElevationSample): Double? {
    val msl = sample.altitudeGpsMslM?.takeIf { it.isFinite() }
    if (msl != null) return msl
    return sample.altitudeGpsEllipsoidM?.takeIf { it.isFinite() }
}

/**
 * Elevation processor implementing ADR-006.
 * Barometer relative + GPS/DEM anchor, hysteresis, drift detection, elevator guard.
 */
open class ElevationProcessor {
    private var anchorPressureHpa: Double? = null
    private var anchorAltitudeM: Double? = null
    private var anchorSource: ElevationSource = ElevationSource.NONE
    private var lastAcceptedAltitude: Double? = null
    private var lastAcceptedTimeNanos: Long = 0L
    private var gainM: Double = 0.0
    private var lossM: Double = 0.0
    private var hasBarometer: Boolean = false

    open fun process(samples: List<ElevationSample>): ElevationDescriptors {
        if (samples.isEmpty()) {
            return emptyDescriptors(
                hasBarometer = false,
                source = ElevationSource.NONE,
                anchorSource = ElevationSource.NONE,
            )
        }

        hasBarometer = samples.any { it.pressureHpa != null && it.pressureHpa.isFinite() }
        establishAnchor(samples)
        return describe(buildProcessedPoints(samples) { computeAltitude(it) })
    }

    private fun describe(processed: List<ProcessedPoint>): ElevationDescriptors {
        if (processed.isEmpty()) {
            return emptyDescriptors(
                hasBarometer = hasBarometer,
                source = if (hasBarometer) ElevationSource.BAROMETER else ElevationSource.NONE,
                anchorSource = anchorSource,
            )
        }

        applyHysteresis(processed)
        val confidence = computeConfidence()
        val uncertainty = if (hasBarometer) BAROMETER_UNCERTAINTY_M else GPS_UNCERTAINTY_M
        return ElevationDescriptors(
            gainM = gainM,
            lossM = lossM,
            netM = gainM - lossM,
            source = resolveSource(),
            confidence = confidence,
            hasBarometer = hasBarometer,
            anchorSource = anchorSource,
            uncertaintyM = uncertainty,
        )
    }

    private fun resolveSource(): ElevationSource =
        when {
            hasBarometer && anchorSource != ElevationSource.NONE -> ElevationSource.BAROMETER
            anchorSource == ElevationSource.GPS_MSL -> ElevationSource.GPS_MSL
            anchorSource == ElevationSource.GPS_ELLIPSOID -> ElevationSource.GPS_ELLIPSOID
            else -> ElevationSource.NONE
        }

    private fun establishAnchor(samples: List<ElevationSample>) {
        if (anchorSource != ElevationSource.NONE) return
        val anchor = bestAnchor(samples) ?: return
        anchorAltitudeM = anchor.altitudeM
        anchorPressureHpa = anchor.pressureHpa
        anchorSource = anchor.source
    }

    private fun computeAltitude(sample: ElevationSample): Double? {
        val barometric = barometricAltitude(sample)
        return barometric ?: gpsAltitude(sample)
    }

    private fun barometricAltitude(sample: ElevationSample): Double? {
        val anchorPressure = anchorPressureHpa ?: return null
        val pressure = sample.pressureHpa
        return if (hasBarometer && pressure != null && pressure.isFinite()) {
            BarometricConverter.pressureToAltitudeDelta(pressure, anchorPressure) + (anchorAltitudeM ?: 0.0)
        } else {
            null
        }
    }

    private fun applyHysteresis(processed: List<ProcessedPoint>) {
        gainM = 0.0
        lossM = 0.0
        lastAcceptedAltitude = null
        lastAcceptedTimeNanos = 0L
        for (point in processed) {
            if (!isElevator(point)) {
                acceptPoint(point)
            }
        }
    }

    private fun acceptPoint(point: ProcessedPoint) {
        val lastAltitude = lastAcceptedAltitude
        if (lastAltitude == null) {
            lastAcceptedAltitude = point.altitude
            lastAcceptedTimeNanos = point.timestampNanos
            return
        }

        val delta = point.altitude - lastAltitude
        if (abs(delta) >= hysteresisM(hasBarometer)) {
            if (delta > 0) {
                gainM += delta
            } else {
                lossM += abs(delta)
            }
            lastAcceptedAltitude = point.altitude
            lastAcceptedTimeNanos = point.timestampNanos
        }
    }

    private fun isElevator(point: ProcessedPoint): Boolean {
        val sample = point.sample
        val speed = sample.speedMps?.takeIf { it.isFinite() }
        val accel = sample.accelMagnitudeMps2?.takeIf { it.isFinite() }
        if (speed == null || accel == null) return false

        // If moving slowly/horizontally but large vertical change with low acceleration.
        val verticalDelta = abs(point.altitude - (lastAcceptedAltitude ?: point.altitude))
        return speed < ElevationParameters.ELEVATOR_DELTA_S_M &&
            verticalDelta > ElevationParameters.ELEVATOR_DELTA_H_M &&
            accel < ElevationParameters.ELEVATOR_ACCEL_QUIET_MPS2
    }

    private fun computeConfidence(): Double {
        var confidence = BASE_CONFIDENCE
        if (hasBarometer && anchorSource != ElevationSource.NONE) confidence += BAROMETER_CONFIDENCE_BONUS
        if (anchorSource == ElevationSource.GPS_MSL) confidence += GPS_MSL_CONFIDENCE_BONUS
        if (anchorSource == ElevationSource.BAROMETER) confidence -= BAROMETER_ANCHOR_PENALTY
        return max(0.0, min(1.0, confidence))
    }
}
