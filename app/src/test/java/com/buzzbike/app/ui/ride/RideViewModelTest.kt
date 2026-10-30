package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.engine.RideEngine
import com.buzzbike.app.domain.ride.session.LocationPermissionChecker
import com.buzzbike.app.domain.ride.session.LocationReading
import com.buzzbike.app.domain.ride.session.LocationSensorPort
import com.buzzbike.app.domain.ride.session.RideServiceController
import com.buzzbike.app.domain.ride.session.RideSessionManager
import com.buzzbike.app.domain.ride.session.RideSessionWriter
import com.buzzbike.app.domain.ride.session.TrackPointWriter
import com.buzzbike.app.domain.ride.session.TrackingManager
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.OutboxEnqueuer
import com.buzzbike.app.domain.sync.RideSyncCoordinator
import com.buzzbike.app.domain.sync.RideSyncDataSource
import com.buzzbike.app.domain.sync.RideSyncInput
import com.buzzbike.app.domain.sync.SyncConfigStore
import com.buzzbike.app.domain.sync.SyncWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the [RideViewModel] ↔ [RideSessionManager] wiring.
 *
 * No Robolectric is needed: the manager is real, the ephemeral Android
 * collaborators are replaced by domain ports, and [viewModelScope] is driven by
 * a test dispatcher installed as `Dispatchers.Main`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun given_idleSession_when_startRide_thenStateIsTracking() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer)

            viewModel.startRide()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isTracking)
            assertEquals(RIDE_ID, viewModel.uiState.value.activeRideId)
            assertEquals(RIDE_ID, writer.startedRideId)
        }

    @Test
    fun given_activeSession_when_stopRide_thenStateIsClearedAndDescriptorsSaved() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer)
            viewModel.startRide()
            advanceUntilIdle()

            viewModel.stopRide()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isTracking)
            assertNull(viewModel.uiState.value.activeRideId)
            assertEquals(RIDE_ID, writer.stoppedRideId)
            assertTrue(writer.savedDescriptors)
        }

    @Test
    fun given_activeSession_when_stopRideTwice_thenStopsOnlyOnce() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer)
            viewModel.startRide()
            advanceUntilIdle()

            viewModel.stopRide()
            viewModel.stopRide()
            advanceUntilIdle()

            assertEquals(1, writer.stopCalls)
            assertFalse(viewModel.uiState.value.isTracking)
        }

    @Test
    fun given_trackingStarts_when_startRide_thenServiceIsStarted() =
        runTest {
            val service = FakeRideServiceController()
            val viewModel = viewModel(service = service)

            viewModel.startRide()
            advanceUntilIdle()

            assertEquals(1, service.startCalls)
            assertEquals(0, service.stopCalls)
        }

    @Test
    fun given_activeSession_when_stopRide_thenServiceIsStopped() =
        runTest {
            val service = FakeRideServiceController()
            val viewModel = viewModel(service = service)
            viewModel.startRide()
            advanceUntilIdle()

            viewModel.stopRide()
            advanceUntilIdle()

            assertEquals(1, service.stopCalls)
        }

    @Test
    fun given_activeSession_when_pauseAndResume_thenStateFlagsToggle() =
        runTest {
            val viewModel = viewModel()
            viewModel.startRide()
            advanceUntilIdle()

            viewModel.pauseRide()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isPaused)
            assertTrue(viewModel.uiState.value.isTracking)

            viewModel.resumeRide()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isPaused)
            assertTrue(viewModel.uiState.value.isTracking)
        }

    private fun TestScope.viewModel(
        writer: RecordingRideSessionWriter = RecordingRideSessionWriter(),
        service: RideServiceController = FakeRideServiceController(),
    ): RideViewModel {
        val trackingManager =
            TrackingManager(
                locationSensor = EmptyLocationSensor,
                trackPointWriter = writer,
                permissionChecker = GrantedPermissionChecker,
            )
        val manager =
            RideSessionManager(
                rideEngine = RideEngine(),
                trackingManager = trackingManager,
                rideSessionWriter = writer,
                scope = backgroundScope,
            )
        val coordinator =
            RideSyncCoordinator(
                dataSource = UnusedRideSyncDataSource,
                enqueuer = UnusedOutboxEnqueuer,
                scheduler = UnusedSyncWorkScheduler,
            )
        return RideViewModel(manager, coordinator, FakeSyncConfigStore(), service)
    }

    private class FakeRideServiceController : RideServiceController {
        var startCalls: Int = 0
            private set

        var stopCalls: Int = 0
            private set

        override fun start() {
            startCalls += 1
        }

        override fun stop() {
            stopCalls += 1
        }
    }

    private class RecordingRideSessionWriter :
        RideSessionWriter,
        TrackPointWriter {
        var startedRideId: String? = null
            private set

        var stoppedRideId: String? = null
            private set

        var stopCalls: Int = 0
            private set

        var savedDescriptors: Boolean = false
            private set

        override suspend fun startRide(): String {
            startedRideId = RIDE_ID
            return RIDE_ID
        }

        override suspend fun stopRide(rideId: String) {
            stopCalls += 1
            stoppedRideId = rideId
        }

        override suspend fun saveRideDescriptors(
            rideId: String,
            descriptors: RideDescriptors,
            elevationGainM: Double,
            elevationLossM: Double,
            caloriesKcal: Double?,
            caloriesLowKcal: Double?,
            caloriesHighKcal: Double?,
            calorieModel: String?,
        ) {
            savedDescriptors = true
        }

        override suspend fun saveTrackPoint(
            rideId: String,
            point: TrackPoint,
        ) = Unit
    }

    private object EmptyLocationSensor : LocationSensorPort {
        override fun locationUpdates(): Flow<LocationReading> = emptyFlow()
    }

    private object GrantedPermissionChecker : LocationPermissionChecker {
        override fun hasFineLocationPermission(): Boolean = true
    }

    private object UnusedRideSyncDataSource : RideSyncDataSource {
        override suspend fun load(rideId: String): RideSyncInput? = null
    }

    private object UnusedOutboxEnqueuer : OutboxEnqueuer {
        override suspend fun enqueue(
            rideId: String,
            payload: ByteArray,
            maxAttempts: Int,
        ) = Unit
    }

    private object UnusedSyncWorkScheduler : SyncWorkScheduler {
        override fun schedule(config: HermesConfig) = Unit

        override fun cancel() = Unit
    }

    /** No persisted sync configuration, so rides are not enqueued. */
    private class FakeSyncConfigStore : SyncConfigStore {
        private var config: HermesConfig? = null

        override fun current(): HermesConfig? = config

        override fun save(config: HermesConfig) {
            this.config = config
        }

        override fun clear() {
            config = null
        }
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
    }
}
