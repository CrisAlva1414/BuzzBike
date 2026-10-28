package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.elevation.ElevationDescriptors
import com.buzzbike.app.domain.elevation.ElevationProcessor
import com.buzzbike.app.domain.elevation.ElevationSample
import com.buzzbike.app.domain.elevation.ElevationSource
import com.buzzbike.app.domain.elevation.PressureSensorPort
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.sensor.motion.MotionReading
import com.buzzbike.app.sensor.pressure.PressureReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for the elevation pipeline inside [TrackingManager].
 * Pressure, motion and the elevation processor are all faked/injected, so no
 * Android runtime is required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingManagerElevationTest {
    @Test
    fun given_pressureAndMotion_when_startElevationTracking_thenProcessorReceivesCombinedSample() =
        runTest {
            val pressures = MutableSharedFlow<PressureReading>(extraBufferCapacity = 8)
            val motions = MutableSharedFlow<MotionReading>(extraBufferCapacity = 8)
            val expected = descriptors(gainM = 12.0, lossM = 0.0)
            val processor = FakeElevationProcessor(expected)
            val manager =
                manager(
                    pressure = FakePressureSensor(pressures),
                    motion = FakeMotionSensor(motions),
                    processor = processor,
                )

            assertTrue(manager.startElevationTracking(RIDE_ID))
            runCurrent()

            pressures.emit(PressureReading(pressureHpa = 1000.0, timestampNanos = T0))
            runCurrent()
            motions.emit(MotionReading(accelMagnitudeMps2 = 9.81, timestampNanos = T0))
            runCurrent()

            assertEquals(expected, manager.elevationDescriptors.first())

            val sample = processor.received.last().single()
            assertEquals(1000.0, sample.pressureHpa ?: Double.NaN, 0.001)
            assertEquals(9.81, sample.accelMagnitudeMps2 ?: Double.NaN, 0.001)
        }

    @Test
    fun given_fallingPressureAndMotion_when_startElevationTracking_thenEmitsPositiveGain() =
        runTest {
            val pressures = MutableSharedFlow<PressureReading>(extraBufferCapacity = 64)
            val motions = MutableSharedFlow<MotionReading>(extraBufferCapacity = 64)
            val manager =
                manager(
                    pressure = FakePressureSensor(pressures),
                    motion = FakeMotionSensor(motions),
                )

            assertTrue(manager.startElevationTracking(RIDE_ID))
            runCurrent()

            repeat(SAMPLE_COUNT) { step ->
                val timestamp = step * NANOS_PER_SECOND
                // Climbing means pressure drops over time.
                pressures.emit(
                    PressureReading(
                        pressureHpa = 1000.0 - step * PRESSURE_DROP_HPA,
                        timestampNanos = timestamp,
                    ),
                )
                runCurrent()
                motions.emit(MotionReading(accelMagnitudeMps2 = RIDING_ACCEL_MPS2, timestampNanos = timestamp))
                runCurrent()
            }

            val result = manager.elevationDescriptors.first()
            assertTrue("gain should be positive, was ${result.gainM}", result.gainM > 0.0)
            assertEquals(0.0, result.lossM, 0.001)
            assertTrue(result.hasBarometer)
        }

    @Test
    fun given_blankRideId_when_startElevationTracking_thenReturnsFalseAndDoesNotSubscribe() =
        runTest {
            val sensor = FakePressureSensor()
            val manager = manager(pressure = sensor)

            assertFalse(manager.startElevationTracking("   "))
            runCurrent()

            assertEquals(0, sensor.collectionCount)
        }

    private fun TestScope.manager(
        pressure: PressureSensorPort = FakePressureSensor(),
        motion: MotionSensorPort = FakeMotionSensor(),
        processor: ElevationProcessor = ElevationProcessor(),
    ): TrackingManager =
        TrackingManager(
            locationSensor = EmptyLocationSensor,
            trackPointWriter = NoOpTrackPointWriter,
            permissionChecker = GrantedPermissionChecker,
            pressureSensor = pressure,
            motionSensor = motion,
            elevationProcessor = processor,
            scope = backgroundScope,
        )

    private fun descriptors(
        gainM: Double,
        lossM: Double,
    ): ElevationDescriptors =
        ElevationDescriptors(
            gainM = gainM,
            lossM = lossM,
            netM = gainM - lossM,
            source = ElevationSource.BAROMETER,
            confidence = 0.8,
            hasBarometer = true,
            anchorSource = ElevationSource.GPS_MSL,
        )

    private class FakePressureSensor(
        private val readings: Flow<PressureReading> = emptyFlow(),
    ) : PressureSensorPort {
        var collectionCount: Int = 0
            private set

        override fun pressureUpdates(): Flow<PressureReading> = readings.onStart { collectionCount++ }
    }

    private class FakeMotionSensor(
        private val readings: Flow<MotionReading> = emptyFlow(),
    ) : MotionSensorPort {
        var collectionCount: Int = 0
            private set

        override fun motionUpdates(): Flow<MotionReading> = readings.onStart { collectionCount++ }
    }

    private class FakeElevationProcessor(
        private val result: ElevationDescriptors,
    ) : ElevationProcessor() {
        val received = mutableListOf<List<ElevationSample>>()

        override fun process(samples: List<ElevationSample>): ElevationDescriptors {
            received += samples.toList()
            return result
        }
    }

    private object EmptyLocationSensor : LocationSensorPort {
        override fun locationUpdates(): Flow<LocationReading> = emptyFlow()
    }

    private object NoOpTrackPointWriter : TrackPointWriter {
        override suspend fun saveTrackPoint(
            rideId: String,
            point: TrackPoint,
        ) = Unit
    }

    private object GrantedPermissionChecker : LocationPermissionChecker {
        override fun hasFineLocationPermission(): Boolean = true
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val T0 = 0L
        private const val SAMPLE_COUNT = 40
        private const val PRESSURE_DROP_HPA = 0.5
        private const val RIDING_ACCEL_MPS2 = 9.81
    }
}
