package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.calories.CalorieModel
import com.buzzbike.app.domain.calories.CalorieResult
import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.elevation.ElevationDescriptors
import com.buzzbike.app.domain.elevation.ElevationSample
import com.buzzbike.app.domain.elevation.ElevationSource
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.engine.RideEngine
import com.buzzbike.app.domain.ride.engine.RideEngineResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [RideSessionManager].
 *
 * [RideEngine], [TrackingManager] and [RideSessionWriter] are faked, so no
 * Android runtime, Room or real sensor is required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideSessionManagerTest {
    @Test
    fun given_validSession_when_startRide_thenIdIsExposedAndTrackingStarts() =
        runTest {
            val writer = FakeRideSessionWriter()
            val tracking = FakeTrackingManager()
            val manager = manager(writer = writer, tracking = tracking)

            val rideId = manager.startRide()

            assertEquals(RIDE_ID, rideId)
            assertEquals(RIDE_ID, tracking.startedRideId)
            assertEquals(RIDE_ID, tracking.elevationRideId)
            val state = manager.uiState.first()
            assertTrue(state.isTracking)
            assertEquals(RIDE_ID, state.activeRideId)
        }

    @Test
    fun given_trackPoints_when_emitted_thenUiStateReflectsEngineResult() =
        runTest {
            val tracking = FakeTrackingManager()
            val engine =
                FakeRideEngine {
                    processResult(
                        distanceM = 1500.0,
                        movingSeconds = 120L,
                        avgSpeedMps = 12.5,
                        caloriesKcal = 210.0,
                    )
                }
            val manager = manager(tracking = tracking, engine = engine)
            manager.startRide()
            runCurrent()

            tracking.points.value = listOf(point(seconds = 0), point(seconds = 10))
            runCurrent()

            val state = manager.uiState.first()
            assertEquals(1500.0, state.distanceM, TOLERANCE)
            assertEquals(120L, state.movingTimeSeconds)
            assertEquals(12.5, state.avgSpeedMps, TOLERANCE)
            assertEquals(210.0, state.caloriesKcal ?: Double.NaN, TOLERANCE)
            assertEquals(2, engine.received.last().size)
        }

    @Test
    fun given_elevationDescriptors_when_emitted_thenGainIsExposed() =
        runTest {
            val tracking = FakeTrackingManager()
            val manager = manager(tracking = tracking)
            manager.startRide()
            runCurrent()

            tracking.elevation.value = elevation(gainM = 42.0)
            runCurrent()

            assertEquals(42.0, manager.uiState.first().elevationGainM, TOLERANCE)
        }

    @Test
    fun given_activeRide_when_stopRide_thenStopsTrackingAndPersistsFinalDescriptors() =
        runTest {
            val writer = FakeRideSessionWriter()
            val tracking = FakeTrackingManager()
            val engine =
                FakeRideEngine {
                    processResult(
                        distanceM = 800.0,
                        movingSeconds = 60L,
                        avgSpeedMps = 13.3,
                        caloriesKcal = 99.0,
                    )
                }
            val manager = manager(writer = writer, tracking = tracking, engine = engine)
            manager.startRide()
            runCurrent()
            tracking.points.value = listOf(point(seconds = 0), point(seconds = 10))
            runCurrent()
            tracking.elevation.value = elevation(gainM = 30.0, lossM = 20.0)
            runCurrent()

            manager.stopRide()

            assertTrue(tracking.stopTrackingCalled)
            assertTrue(tracking.stopElevationCalled)
            assertEquals(RIDE_ID, writer.stoppedRideId)
            val saved = writer.saved.single()
            assertEquals(800.0, saved.descriptors.distanceM, TOLERANCE)
            assertEquals(30.0, saved.elevationGainM, TOLERANCE)
            assertEquals(20.0, saved.elevationLossM, TOLERANCE)
            assertEquals(99.0, saved.caloriesKcal ?: Double.NaN, TOLERANCE)
            val state = manager.uiState.first()
            assertFalse(state.isTracking)
            assertNull(state.activeRideId)
        }

    @Test
    fun given_completedSession_when_stopRide_thenEveryFinalDescriptorIsPersisted() =
        runTest {
            val writer = FakeRideSessionWriter()
            val tracking = FakeTrackingManager()
            val engine =
                FakeRideEngine {
                    processResult(
                        distanceM = 1234.5,
                        movingSeconds = 321L,
                        avgSpeedMps = 8.4,
                        caloriesKcal = 250.0,
                    )
                }
            val manager = manager(writer = writer, tracking = tracking, engine = engine)
            manager.startRide()
            runCurrent()
            tracking.points.value = listOf(point(seconds = 0), point(seconds = 321))
            runCurrent()
            tracking.elevation.value = elevation(gainM = 75.0, lossM = 40.0)
            runCurrent()

            manager.stopRide()

            val saved = writer.saved.single()
            assertEquals(RIDE_ID, saved.rideId)
            assertEquals(1234.5, saved.descriptors.distanceM, TOLERANCE)
            assertEquals(321L, saved.descriptors.movingSeconds)
            assertEquals(8.4, saved.descriptors.avgSpeedMps, TOLERANCE)
            assertEquals(8.4, saved.descriptors.maxSpeedMps, TOLERANCE)
            assertEquals(75.0, saved.elevationGainM, TOLERANCE)
            assertEquals(40.0, saved.elevationLossM, TOLERANCE)
            assertEquals(250.0, saved.caloriesKcal ?: Double.NaN, TOLERANCE)
        }

    private fun TestScope.manager(
        writer: FakeRideSessionWriter = FakeRideSessionWriter(),
        tracking: FakeTrackingManager = FakeTrackingManager(),
        engine: RideEngine = FakeRideEngine { processResult() },
    ): RideSessionManager =
        RideSessionManager(
            rideEngine = engine,
            trackingManager = tracking,
            rideSessionWriter = writer,
            scope = backgroundScope,
        )

    private fun point(seconds: Long): TrackPoint =
        TrackPoint(
            timestampMonotonicNanos = seconds * NANOS_PER_SECOND,
            lat = BASE_LAT,
            lon = BASE_LON,
            accuracyH = ACCURACY_M,
        )

    private fun elevation(
        gainM: Double,
        lossM: Double = 0.0,
    ): ElevationDescriptors =
        ElevationDescriptors(
            gainM = gainM,
            lossM = lossM,
            netM = gainM - lossM,
            source = ElevationSource.BAROMETER,
            confidence = CONFIDENCE,
            hasBarometer = true,
            anchorSource = ElevationSource.GPS_MSL,
        )

    private fun processResult(
        distanceM: Double = 0.0,
        movingSeconds: Long = 0L,
        avgSpeedMps: Double = 0.0,
        caloriesKcal: Double? = null,
    ): RideEngineResult =
        RideEngineResult(
            rideDescriptors =
                RideDescriptors(
                    distanceM = distanceM,
                    elapsedSeconds = movingSeconds,
                    movingSeconds = movingSeconds,
                    stoppedSeconds = 0L,
                    avgMovingSpeedMps = avgSpeedMps,
                    avgSpeedMps = avgSpeedMps,
                    maxSpeedMps = avgSpeedMps,
                    stopCount = 0,
                    stopDurationsSeconds = emptyList(),
                    acceptedPoints = 0,
                    rejectedPoints = 0,
                    gapCount = 0,
                    distanceIsLowerBound = false,
                    modelVersion = MODEL_VERSION,
                ),
            elevationDescriptors = elevation(gainM = 0.0),
            calorieResult = caloriesKcal?.let { calorieResult(it) },
            acceptedPoints = 0,
            rejectedPoints = 0,
        )

    private fun calorieResult(kcal: Double): CalorieResult =
        CalorieResult(
            totalKcal = kcal,
            activeKcal = kcal,
            metMinutes = 0.0,
            model = CalorieModel.COMPENDIUM_VELOCITY,
            uncertaintyMinPercent = UNCERTAINTY_MIN_PERCENT,
            uncertaintyMaxPercent = UNCERTAINTY_MAX_PERCENT,
            hasProfile = false,
            missingInputs = emptyList(),
        )

    private data class SavedDescriptors(
        val rideId: String,
        val descriptors: RideDescriptors,
        val elevationGainM: Double,
        val elevationLossM: Double,
        val caloriesKcal: Double?,
    )

    private class FakeRideSessionWriter : RideSessionWriter {
        var stoppedRideId: String? = null
            private set

        val saved = mutableListOf<SavedDescriptors>()

        override suspend fun startRide(): String = RIDE_ID

        override suspend fun stopRide(rideId: String) {
            stoppedRideId = rideId
        }

        override suspend fun saveRideDescriptors(
            rideId: String,
            descriptors: RideDescriptors,
            elevationGainM: Double,
            elevationLossM: Double,
            caloriesKcal: Double?,
        ) {
            saved += SavedDescriptors(rideId, descriptors, elevationGainM, elevationLossM, caloriesKcal)
        }
    }

    private class FakeRideEngine(
        private val onProcess: (List<TrackPoint>) -> RideEngineResult,
    ) : RideEngine() {
        val received = mutableListOf<List<TrackPoint>>()

        override fun processRide(
            rawPoints: List<TrackPoint>,
            elevationSamples: List<ElevationSample>,
            profile: UserProfile,
        ): RideEngineResult {
            received += rawPoints
            return onProcess(rawPoints)
        }
    }

    private class FakeTrackingManager :
        TrackingManager(
            locationSensor = EmptyLocationSensor,
            trackPointWriter = NoOpTrackPointWriter,
            permissionChecker = GrantedPermissionChecker,
        ) {
        val points = MutableStateFlow<List<TrackPoint>>(emptyList())
        val elevation = MutableStateFlow(emptyElevation())

        var startedRideId: String? = null
            private set

        var elevationRideId: String? = null
            private set

        var stopTrackingCalled: Boolean = false
            private set

        var stopElevationCalled: Boolean = false
            private set

        override val trackPoints: Flow<List<TrackPoint>>
            get() = points

        override val elevationDescriptors: Flow<ElevationDescriptors>
            get() = elevation

        override fun startTracking(rideId: String): Boolean {
            startedRideId = rideId
            return true
        }

        override fun stopTracking() {
            stopTrackingCalled = true
        }

        override fun startElevationTracking(rideId: String): Boolean {
            elevationRideId = rideId
            return true
        }

        override fun stopElevationTracking() {
            stopElevationCalled = true
        }

        private companion object {
            fun emptyElevation(): ElevationDescriptors =
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
        private const val BASE_LAT = -33.45
        private const val BASE_LON = -70.66
        private const val ACCURACY_M = 5.0
        private const val TOLERANCE = 0.001
        private const val CONFIDENCE = 0.8
        private const val MODEL_VERSION = "test"
        private const val UNCERTAINTY_MIN_PERCENT = 20
        private const val UNCERTAINTY_MAX_PERCENT = 30
    }
}
