package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.elevation.ElevationDescriptors
import com.buzzbike.app.domain.elevation.ElevationProcessor
import com.buzzbike.app.domain.elevation.ElevationSample
import com.buzzbike.app.domain.elevation.ElevationSource
import com.buzzbike.app.domain.elevation.PressureSensorPort
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.sensor.motion.MotionReading
import com.buzzbike.app.sensor.pressure.PressureReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.TreeMap
import kotlin.math.abs

/**
 * Coordinates a ride tracking session: gates on the location permission,
 * subscribes to [LocationSensorPort], converts readings to [TrackPoint] and
 * persists them through [TrackPointWriter]. It also combines barometer and
 * accelerometer readings into [ElevationSample] values and feeds them to
 * [ElevationProcessor].
 *
 * The class has no Android dependencies: sensors and the permission check are
 * injected as ports, so the whole flow is testable on the JVM.
 *
 * The constructor intentionally takes seven collaborators: the three original
 * tracking dependencies, the three elevation dependencies added in slice 1.2,
 * and the coroutine scope.
 *
 * @param pressureSensor barometer port. Defaults to an empty source so existing
 *   call sites (and devices without a barometer) keep working; production wiring
 *   should pass the platform adapter.
 * @param motionSensor accelerometer port. Defaults to an empty source for the
 *   same reason.
 * @param elevationProcessor stateful elevation engine. One instance per manager.
 * @param scope scope used to collect the sensor flows. Callers that own the
 *   manager lifecycle should pass their own scope; the default exists for
 *   simple use and is never cancelled automatically.
 */
@Suppress("LongParameterList")
open class TrackingManager(
    private val locationSensor: LocationSensorPort,
    private val trackPointWriter: TrackPointWriter,
    private val permissionChecker: LocationPermissionChecker,
    private val pressureSensor: PressureSensorPort = NoOpPressureSensorPort,
    private val motionSensor: MotionSensorPort = NoOpMotionSensorPort,
    private val elevationProcessor: ElevationProcessor = ElevationProcessor(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _trackPoints = MutableStateFlow<List<TrackPoint>>(emptyList())

    /** Points accumulated during the current/last session, in arrival order. */
    open val trackPoints: Flow<List<TrackPoint>> = _trackPoints.asStateFlow()

    private val _elevationDescriptors = MutableStateFlow(EMPTY_ELEVATION_DESCRIPTORS)

    /** Latest elevation descriptors for the active elevation session. */
    open val elevationDescriptors: Flow<ElevationDescriptors> = _elevationDescriptors.asStateFlow()

    private var trackingJob: Job? = null
    private var elevationJob: Job? = null

    /**
     * Starts collecting location readings for [rideId].
     *
     * Returns `false` (and does not subscribe) when the fine location
     * permission is missing or [rideId] is blank. A second call while already
     * tracking is a no-op that returns `true`.
     */
    @Synchronized
    open fun startTracking(rideId: String): Boolean {
        if (rideId.isBlank() || !permissionChecker.hasFineLocationPermission()) {
            return false
        }

        startCollectionIfIdle(rideId)
        return true
    }

    /** Stops the sensor subscription. Accumulated points are kept. */
    @Synchronized
    open fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
    }

    /**
     * Starts collecting barometer and accelerometer readings for [rideId],
     * combining them into [ElevationSample] values and emitting the descriptors
     * computed by [ElevationProcessor].
     *
     * Returns `false` when [rideId] is blank. A second call while already
     * tracking elevation is a no-op that returns `true`.
     */
    @Synchronized
    open fun startElevationTracking(rideId: String): Boolean {
        if (rideId.isBlank()) return false

        startElevationCollectionIfIdle()
        return true
    }

    /** Stops the elevation subscription. The last descriptors are kept. */
    @Synchronized
    open fun stopElevationTracking() {
        elevationJob?.cancel()
        elevationJob = null
    }

    private fun startElevationCollectionIfIdle() {
        if (elevationJob?.isActive == true) return

        _elevationDescriptors.value = EMPTY_ELEVATION_DESCRIPTORS
        elevationJob =
            scope.launch {
                val assembler = ElevationSampleAssembler()
                merge(
                    pressureSensor.pressureUpdates().map { SensorEvent.Pressure(it) },
                    motionSensor.motionUpdates().map { SensorEvent.Motion(it) },
                ).collect { event ->
                    assembler.accept(event)?.let {
                        _elevationDescriptors.value = elevationProcessor.process(assembler.snapshots())
                    }
                }
            }
    }

    private fun startCollectionIfIdle(rideId: String) {
        if (trackingJob?.isActive == true) return

        _trackPoints.value = emptyList()
        trackingJob =
            scope.launch {
                locationSensor.locationUpdates().collect { reading ->
                    val point = reading.toTrackPointOrNull()
                    if (point != null) {
                        trackPointWriter.saveTrackPoint(rideId, point)
                        _trackPoints.value = _trackPoints.value + point
                    }
                }
            }
    }

    private fun LocationReading.hasValidCoordinates(): Boolean {
        val coordinatesAreFinite = lat.isFinite() && lon.isFinite()
        val coordinatesAreInRange = lat in LAT_RANGE && lon in LON_RANGE
        return coordinatesAreFinite && coordinatesAreInRange
    }

    private fun LocationReading.toTrackPointOrNull(): TrackPoint? =
        if (hasValidCoordinates()) {
            TrackPoint(
                timestampMonotonicNanos = timestampNanos,
                lat = lat,
                lon = lon,
                altitudeEllipsoidM = altitudeEllipsoidM,
                accuracyH = accuracyH,
                accuracyV = accuracyV,
                speedMps = speedMps,
                bearing = bearing,
            )
        } else {
            null
        }

    private companion object {
        private val LAT_RANGE = -90.0..90.0
        private val LON_RANGE = -180.0..180.0
        private val EMPTY_ELEVATION_DESCRIPTORS =
            ElevationDescriptors(
                gainM = 0.0,
                lossM = 0.0,
                netM = 0.0,
                source = ElevationSource.NONE,
                confidence = 0.0,
                hasBarometer = false,
                anchorSource = ElevationSource.NONE,
            )
    }
}

/** Tagged event so both sensor flows can be merged into a single collector. */
private sealed interface SensorEvent {
    data class Pressure(
        val reading: PressureReading,
    ) : SensorEvent

    data class Motion(
        val reading: MotionReading,
    ) : SensorEvent
}

/**
 * Builds [ElevationSample] values by pairing pressure and motion readings with
 * the closest timestamp within [MAX_PAIRING_WINDOW_NANOS]. Samples are keyed by
 * timestamp so a late motion reading enriches the sample created by pressure
 * instead of adding a duplicate.
 */
private class ElevationSampleAssembler {
    private val pressures = ArrayDeque<PressureReading>()
    private val motions = ArrayDeque<MotionReading>()
    private val samples = TreeMap<Long, ElevationSample>()

    fun accept(event: SensorEvent): ElevationSample? =
        when (event) {
            is SensorEvent.Pressure -> acceptPressure(event.reading)
            is SensorEvent.Motion -> acceptMotion(event.reading)
        }

    fun snapshots(): List<ElevationSample> = samples.values.toList()

    private fun acceptPressure(reading: PressureReading): ElevationSample? {
        if (!reading.pressureHpa.isFinite() || reading.pressureHpa <= 0.0) return null

        pressures.addLast(reading)
        pressures.trimTo(MAX_BUFFERED_READINGS)
        return upsert(
            ElevationSample(
                timestampNanos = reading.timestampNanos,
                pressureHpa = reading.pressureHpa,
                accelMagnitudeMps2 = nearestMotion(reading.timestampNanos)?.accelMagnitudeMps2,
            ),
        )
    }

    private fun acceptMotion(reading: MotionReading): ElevationSample? {
        if (!reading.accelMagnitudeMps2.isFinite()) return null

        motions.addLast(reading)
        motions.trimTo(MAX_BUFFERED_READINGS)
        return nearestPressure(reading.timestampNanos)?.let { pressure ->
            upsert(
                ElevationSample(
                    timestampNanos = pressure.timestampNanos,
                    pressureHpa = pressure.pressureHpa,
                    accelMagnitudeMps2 = reading.accelMagnitudeMps2,
                ),
            )
        }
    }

    private fun upsert(sample: ElevationSample): ElevationSample {
        val merged =
            samples[sample.timestampNanos]?.let { existing ->
                existing.copy(
                    pressureHpa = sample.pressureHpa ?: existing.pressureHpa,
                    accelMagnitudeMps2 = sample.accelMagnitudeMps2 ?: existing.accelMagnitudeMps2,
                )
            } ?: sample
        samples[sample.timestampNanos] = merged
        return merged
    }

    private fun nearestMotion(timestampNanos: Long): MotionReading? =
        motions
            .asSequence()
            .filter { abs(it.timestampNanos - timestampNanos) <= MAX_PAIRING_WINDOW_NANOS }
            .minByOrNull { abs(it.timestampNanos - timestampNanos) }

    private fun nearestPressure(timestampNanos: Long): PressureReading? =
        pressures
            .asSequence()
            .filter { abs(it.timestampNanos - timestampNanos) <= MAX_PAIRING_WINDOW_NANOS }
            .minByOrNull { abs(it.timestampNanos - timestampNanos) }

    private fun <T> ArrayDeque<T>.trimTo(limit: Int) {
        while (size > limit) {
            removeFirst()
        }
    }

    private companion object {
        // One second is wide enough to pair readings from both sensors while
        // staying narrow enough to avoid pairing unrelated moments.
        private const val MAX_PAIRING_WINDOW_NANOS = 1_000_000_000L
        private const val MAX_BUFFERED_READINGS = 32
    }
}

private object NoOpPressureSensorPort : PressureSensorPort {
    override fun pressureUpdates(): Flow<PressureReading> = emptyFlow()
}

private object NoOpMotionSensorPort : MotionSensorPort {
    override fun motionUpdates(): Flow<MotionReading> = emptyFlow()
}
