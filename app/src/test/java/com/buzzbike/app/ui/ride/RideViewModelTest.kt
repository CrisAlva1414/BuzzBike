package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.calories.UserProfileStore
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.engine.RideEngine
import com.buzzbike.app.domain.ride.session.GpsStatus
import com.buzzbike.app.domain.ride.session.LocationPermissionChecker
import com.buzzbike.app.domain.ride.session.LocationReading
import com.buzzbike.app.domain.ride.session.LocationSensorPort
import com.buzzbike.app.domain.ride.session.RideServiceController
import com.buzzbike.app.domain.ride.session.RideSessionManager
import com.buzzbike.app.domain.ride.session.RideSessionState
import com.buzzbike.app.domain.ride.session.RideSessionWriter
import com.buzzbike.app.domain.ride.session.TrackPointWriter
import com.buzzbike.app.domain.ride.session.TrackingManager
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.OutboxEnqueuer
import com.buzzbike.app.domain.sync.RideSyncCoordinator
import com.buzzbike.app.domain.sync.RideSyncDataSource
import com.buzzbike.app.domain.sync.RideSyncInput
import com.buzzbike.app.domain.sync.SecretSealer
import com.buzzbike.app.domain.sync.SecretStorage
import com.buzzbike.app.domain.sync.SyncConfigStore
import com.buzzbike.app.domain.sync.SyncGatekeeper
import com.buzzbike.app.domain.sync.SyncMutationGate
import com.buzzbike.app.domain.sync.SyncWorkScheduler
import com.buzzbike.app.domain.sync.UnsealedSecret
import com.buzzbike.app.domain.wear.Bike
import com.buzzbike.app.domain.wear.BikeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
    fun given_permission_when_startRide_thenRecordingAndServiceStarted() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val service = FakeRideServiceController()
            val viewModel = viewModel(writer = writer, service = service)

            viewModel.startRide()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is RideSessionState.Recording)
            assertEquals(RIDE_ID, (viewModel.state.value as RideSessionState.Recording).rideId)
            assertEquals(RIDE_ID, writer.startedRideId)
            assertEquals(1, service.startCalls)
            assertEquals(0, service.stopCalls)
        }

    @Test
    fun given_savedProfile_when_startRide_thenPassesSnapshotAndExposesHasProfile() =
        runTest {
            val profile = UserProfile(weightKg = 72.5, heightCm = 178.0, ageYears = 34, isMale = true)
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer, profileStore = FakeProfileStore(profile))

            viewModel.startRide()
            advanceUntilIdle()

            assertEquals(profile, writer.startedProfile)
            val recording = viewModel.state.value as RideSessionState.Recording
            assertTrue(recording.live.hasProfile)
        }

    @Test
    fun given_noPermission_when_startRide_thenReadyAndServiceNotStarted() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val service = FakeRideServiceController()
            val viewModel = viewModel(writer = writer, service = service, permissionChecker = DeniedPermissionChecker)

            viewModel.startRide()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state is RideSessionState.Ready)
            assertEquals(GpsStatus.NO_PERMISSION, (state as RideSessionState.Ready).gps)
            assertNull(writer.startedRideId)
            assertEquals(0, service.startCalls)
        }

    @Test
    fun given_noPermission_when_permissionGrantedAndRefreshed_thenReadyAndStartWorks() =
        runTest {
            val checker = MutablePermissionChecker(granted = false)
            val writer = RecordingRideSessionWriter()
            val service = FakeRideServiceController()
            val viewModel = viewModel(writer = writer, service = service, permissionChecker = checker)

            advanceUntilIdle()
            val denied = viewModel.state.value
            assertTrue(denied is RideSessionState.Ready)
            assertEquals(GpsStatus.NO_PERMISSION, (denied as RideSessionState.Ready).gps)

            checker.granted = true
            viewModel.onLocationPermissionResult()
            advanceUntilIdle()

            val refreshed = viewModel.state.value
            assertTrue(refreshed is RideSessionState.Ready)
            assertEquals(GpsStatus.SEARCHING, (refreshed as RideSessionState.Ready).gps)
            assertNull(writer.startedRideId)

            viewModel.startRide()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is RideSessionState.Recording)
            assertEquals(1, service.startCalls)
        }

    @Test
    fun given_recordingSession_when_onLocationPermissionResult_thenNoOp() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer)
            viewModel.startRide()
            advanceUntilIdle()
            val recording = viewModel.state.value
            assertTrue(recording is RideSessionState.Recording)
            val rideId = (recording as RideSessionState.Recording).rideId

            viewModel.onLocationPermissionResult()
            advanceUntilIdle()

            val after = viewModel.state.value
            assertTrue(after is RideSessionState.Recording)
            assertEquals(rideId, (after as RideSessionState.Recording).rideId)
        }

    @Test
    fun given_viewModelConstruction_when_restoreRuns_thenStateLeavesIdle() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer)

            advanceUntilIdle()

            assertEquals(1, writer.findActiveRideCalls)
            assertTrue(viewModel.state.value is RideSessionState.Ready)
        }

    @Test
    fun given_persistedRide_when_salvageRecoverableRide_thenDelegatesAndFinishes() =
        runTest {
            val writer =
                RecordingRideSessionWriter(initialActiveRideId = RIDE_ID).apply {
                    loadedPoints =
                        listOf(
                            TrackPoint(timestampMonotonicNanos = 0L, lat = BASE_LAT, lon = BASE_LON, speedMps = 5.0),
                            TrackPoint(
                                timestampMonotonicNanos = NANOS_PER_SECOND,
                                lat = BASE_LAT + LAT_STEP,
                                lon = BASE_LON,
                                speedMps = 5.0,
                            ),
                        )
                }
            val service = FakeRideServiceController()
            val viewModel = viewModel(writer = writer, service = service)
            advanceUntilIdle()
            assertTrue(viewModel.state.value is RideSessionState.Recoverable)

            viewModel.salvageRecoverableRide()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state is RideSessionState.Finished)
            assertEquals(RIDE_ID, (state as RideSessionState.Finished).rideId)
            assertTrue(writer.savedDescriptors)
            assertEquals(1, writer.stopCalls)
            assertEquals(0, service.startCalls)
        }

    @Test
    fun given_liveProfileChanged_when_salvage_thenUsesPersistedSnapshotWithoutReadingStore() =
        runTest {
            val persisted = UserProfile(weightKg = 72.5, heightCm = 178.0, ageYears = 34, isMale = true)
            val live = UserProfile(weightKg = 60.0, heightCm = 160.0, ageYears = 20, isMale = false)
            val writer =
                RecordingRideSessionWriter(initialActiveRideId = RIDE_ID).apply {
                    persistedProfileValue = persisted
                    loadedPoints =
                        listOf(
                            TrackPoint(timestampMonotonicNanos = 0L, lat = BASE_LAT, lon = BASE_LON, speedMps = 5.0),
                            TrackPoint(
                                timestampMonotonicNanos = NANOS_PER_SECOND,
                                lat = BASE_LAT + LAT_STEP,
                                lon = BASE_LON,
                                speedMps = 5.0,
                            ),
                        )
                }
            val store = FakeProfileStore(live)
            val viewModel = viewModel(writer = writer, profileStore = store)
            advanceUntilIdle()
            assertTrue(viewModel.state.value is RideSessionState.Recoverable)

            viewModel.salvageRecoverableRide()
            advanceUntilIdle()

            assertEquals(0, store.getCalls)
            assertEquals(1, writer.persistedProfileCalls)
            assertEquals(persisted, writer.savedProfile)
            val finished = viewModel.state.value as RideSessionState.Finished
            assertTrue(finished.summary.hasProfile)
        }

    @Test
    fun given_idle_when_startRideTwiceInSameFrame_thenStartsOnlyOnce() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val service = FakeRideServiceController()
            val viewModel = viewModel(writer = writer, service = service)

            viewModel.startRide()
            viewModel.startRide()
            advanceUntilIdle()

            assertEquals(1, writer.startCalls)
            assertEquals(1, service.startCalls)
            assertTrue(viewModel.state.value is RideSessionState.Recording)
        }

    @Test
    fun given_recordingSession_when_stopRide_thenFinishedAndDescriptorsSaved() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val service = FakeRideServiceController()
            val viewModel = viewModel(writer = writer, service = service)
            viewModel.startRide()
            advanceUntilIdle()

            viewModel.stopRide()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is RideSessionState.Finished)
            assertEquals(RIDE_ID, writer.stoppedRideId)
            assertTrue(writer.savedDescriptors)
            assertEquals(1, service.stopCalls)
        }

    @Test
    fun given_recordingSession_when_stopRideTwice_thenStopsOnlyOnce() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer)
            viewModel.startRide()
            advanceUntilIdle()

            viewModel.stopRide()
            viewModel.stopRide()
            advanceUntilIdle()

            assertEquals(1, writer.stopCalls)
            assertTrue(viewModel.state.value is RideSessionState.Finished)
        }

    @Test
    fun given_recordingSession_when_pauseAndResume_thenStateToggles() =
        runTest {
            val viewModel = viewModel()
            viewModel.startRide()
            advanceUntilIdle()

            viewModel.pauseRide()
            advanceUntilIdle()
            assertTrue(viewModel.state.value is RideSessionState.Paused)

            viewModel.resumeRide()
            advanceUntilIdle()
            assertTrue(viewModel.state.value is RideSessionState.Recording)
        }

    @Test
    fun given_recordingSession_when_discardRide_thenRideDeletedAndReady() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val service = FakeRideServiceController()
            val viewModel = viewModel(writer = writer, service = service)
            viewModel.startRide()
            advanceUntilIdle()

            viewModel.discardRide()
            advanceUntilIdle()

            assertEquals(RIDE_ID, writer.deletedRideId)
            assertTrue(viewModel.state.value is RideSessionState.Ready)
            assertFalse(writer.savedDescriptors)
            assertEquals(1, service.stopCalls)
        }

    @Test
    fun given_deleteFails_when_discardRide_thenServiceIsStoppedAnyway() =
        runTest {
            val writer =
                RecordingRideSessionWriter().apply {
                    // Cancellation keeps the failure scoped to the
                    // viewModelScope coroutine (a RuntimeException would be
                    // reported as uncaught by runTest) while still exercising
                    // the same try/finally cleanup path of discardRide.
                    deleteFailure = kotlinx.coroutines.CancellationException("simulated delete failure")
                }
            val service = FakeRideServiceController()
            val viewModel = viewModel(writer = writer, service = service)
            viewModel.startRide()
            advanceUntilIdle()

            viewModel.discardRide()
            advanceUntilIdle()

            assertTrue(writer.deleteAttempted)
            // The FGS must be stopped by the try/finally even when the delete
            // transaction fails.
            assertEquals(1, service.stopCalls)
        }

    @Test
    fun given_availableBike_when_nothingSelected_then_startRidePersistsNoBike() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer, bikeRepository = FakeBikeRepository(listOf(bike(BIKE_ID))))
            advanceUntilIdle()

            viewModel.startRide()
            advanceUntilIdle()

            assertNull(viewModel.bikeSelection.value.selectedBikeId)
            assertNull(writer.startedBikeId)
        }

    @Test
    fun given_selectedBike_when_startRide_thenWriterReceivesBikeId() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer, bikeRepository = FakeBikeRepository(listOf(bike(BIKE_ID))))
            advanceUntilIdle()

            viewModel.selectBike(BIKE_ID)
            advanceUntilIdle()
            assertEquals(BIKE_ID, viewModel.bikeSelection.value.selectedBikeId)

            viewModel.startRide()
            advanceUntilIdle()

            assertEquals(BIKE_ID, writer.startedBikeId)
        }

    @Test
    fun given_rideStartedWithBike_when_garageChanges_thenCapturedBikeIdIsUnchanged() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val bikeRepository = FakeBikeRepository(listOf(bike(BIKE_ID)))
            val viewModel = viewModel(writer = writer, bikeRepository = bikeRepository)
            advanceUntilIdle()
            viewModel.selectBike(BIKE_ID)
            advanceUntilIdle()

            viewModel.startRide()
            advanceUntilIdle()

            bikeRepository.bikes.value = listOf(bike(OTHER_BIKE_ID))
            advanceUntilIdle()

            assertEquals(BIKE_ID, writer.startedBikeId)
            assertTrue(viewModel.state.value is RideSessionState.Recording)
        }

    @Test
    fun given_startInFlight_when_selectBike_thenSelectionIsIgnored() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel = viewModel(writer = writer, bikeRepository = FakeBikeRepository(listOf(bike(BIKE_ID))))
            advanceUntilIdle()

            // beginStart flips the busy flag synchronously; the start coroutine
            // is still queued on the test dispatcher.
            viewModel.startRide()
            viewModel.selectBike(BIKE_ID)
            advanceUntilIdle()

            assertNull(viewModel.bikeSelection.value.selectedBikeId)
            assertNull(writer.startedBikeId)
        }

    @Test
    fun given_archivedBike_when_selecting_thenItIsNotOfferedNorSelected() =
        runTest {
            val writer = RecordingRideSessionWriter()
            val viewModel =
                viewModel(
                    writer = writer,
                    bikeRepository = FakeBikeRepository(listOf(bike(BIKE_ID), bike(OTHER_BIKE_ID, archived = true))),
                )
            advanceUntilIdle()

            assertEquals(
                listOf(BIKE_ID),
                viewModel.bikeSelection.value.bikes
                    .map { it.id },
            )

            viewModel.selectBike(OTHER_BIKE_ID)
            advanceUntilIdle()

            assertNull(viewModel.bikeSelection.value.selectedBikeId)
            viewModel.startRide()
            advanceUntilIdle()
            assertNull(writer.startedBikeId)
        }

    private fun bike(
        id: String,
        archived: Boolean = false,
    ): Bike =
        Bike(
            id = id,
            name = "Bike $id",
            notes = null,
            createdAtMillis = 1_000L,
            isArchived = archived,
        )

    @Suppress("LongParameterList")
    private fun TestScope.viewModel(
        writer: RecordingRideSessionWriter = RecordingRideSessionWriter(),
        service: RideServiceController = FakeRideServiceController(),
        permissionChecker: LocationPermissionChecker = GrantedPermissionChecker,
        profileStore: UserProfileStore = FakeProfileStore(),
        engine: RideEngine = RideEngine(),
        bikeRepository: FakeBikeRepository = FakeBikeRepository(),
    ): RideViewModel {
        val trackingManager =
            TrackingManager(
                locationSensor = EmptyLocationSensor,
                trackPointWriter = writer,
                permissionChecker = permissionChecker,
            )
        val manager =
            RideSessionManager(
                rideEngine = engine,
                trackingManager = trackingManager,
                rideSessionWriter = writer,
                permissionChecker = permissionChecker,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )
        val coordinator =
            RideSyncCoordinator(
                dataSource = UnusedRideSyncDataSource,
                enqueuer = UnusedOutboxEnqueuer,
                scheduler = UnusedSyncWorkScheduler,
                gatekeeper =
                    SyncGatekeeper(
                        configStore = FakeSyncConfigStore(),
                        secretManager = HmacSecretManager(UnusedSecretSealer, EmptySecretStorage),
                        gate = SyncMutationGate(),
                    ),
            )
        return RideViewModel(manager, coordinator, service, profileStore, bikeRepository)
    }

    private class FakeBikeRepository(
        bikes: List<Bike> = emptyList(),
    ) : BikeRepository {
        val bikes = MutableStateFlow(bikes)

        override fun observeBikes(): Flow<List<Bike>> = bikes

        override suspend fun getBike(id: String): Bike? = bikes.value.firstOrNull { it.id == id }

        override suspend fun createBike(
            name: String,
            notes: String?,
        ): String = error("not used")

        override suspend fun updateBike(bike: Bike) = Unit

        override suspend fun deleteBike(id: String) = Unit

        override suspend fun assignRideToBike(
            rideId: String,
            bikeId: String?,
        ) = Unit
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

    private class RecordingRideSessionWriter(
        initialActiveRideId: String? = null,
    ) : RideSessionWriter,
        TrackPointWriter {
        var startedRideId: String? = null
            private set

        var startedProfile: UserProfile? = null
            private set

        var startedBikeId: String? = null
            private set

        var startCalls: Int = 0
            private set

        var stoppedRideId: String? = null
            private set

        var deletedRideId: String? = null
            private set

        var deleteFailure: Throwable? = null

        var deleteAttempted: Boolean = false
            private set

        var stopCalls: Int = 0
            private set

        var savedDescriptors: Boolean = false
            private set

        var savedProfile: UserProfile? = null
            private set

        var findActiveRideCalls: Int = 0
            private set

        var loadedPoints: List<TrackPoint> = emptyList()

        var persistedProfileValue: UserProfile? = null

        var persistedProfileCalls: Int = 0
            private set

        private var activeRideId: String? = initialActiveRideId

        override suspend fun startRide(
            profile: UserProfile?,
            bikeId: String?,
        ): String {
            startCalls += 1
            startedProfile = profile
            startedBikeId = bikeId
            startedRideId = RIDE_ID
            activeRideId = RIDE_ID
            return RIDE_ID
        }

        override suspend fun findActiveRideId(): String? {
            findActiveRideCalls += 1
            return activeRideId
        }

        override suspend fun persistedProfile(rideId: String): UserProfile? {
            persistedProfileCalls += 1
            return persistedProfileValue
        }

        override suspend fun loadTrackPoints(rideId: String): List<TrackPoint> = loadedPoints

        override suspend fun stopRide(rideId: String) {
            stopCalls += 1
            stoppedRideId = rideId
            activeRideId = null
        }

        override suspend fun deleteRide(rideId: String) {
            deleteAttempted = true
            deleteFailure?.let { throw it }
            deletedRideId = rideId
            activeRideId = null
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
            profile: UserProfile?,
        ) {
            savedDescriptors = true
            savedProfile = profile
        }

        override suspend fun saveTrackPoint(
            rideId: String,
            point: TrackPoint,
        ) = Unit
    }

    private class FakeProfileStore(
        var profile: UserProfile? = null,
    ) : UserProfileStore {
        var getCalls: Int = 0
            private set

        override fun observe(): Flow<UserProfile?> = flowOf(profile)

        override suspend fun get(): UserProfile? {
            getCalls += 1
            return profile
        }

        override suspend fun save(profile: UserProfile) {
            this.profile = profile
        }

        override suspend fun clear() {
            profile = null
        }
    }

    private object EmptyLocationSensor : LocationSensorPort {
        override fun locationUpdates(): Flow<LocationReading> = emptyFlow()
    }

    private class MutablePermissionChecker(
        var granted: Boolean,
    ) : LocationPermissionChecker {
        override fun hasFineLocationPermission(): Boolean = granted
    }

    private object GrantedPermissionChecker : LocationPermissionChecker {
        override fun hasFineLocationPermission(): Boolean = true
    }

    private object DeniedPermissionChecker : LocationPermissionChecker {
        override fun hasFineLocationPermission(): Boolean = false
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

    private object UnusedSecretSealer : SecretSealer {
        override fun seal(
            plaintext: ByteArray,
            purpose: String,
        ): ByteArray = plaintext.copyOf()

        override fun unseal(
            sealed: ByteArray,
            purpose: String,
        ): UnsealedSecret = UnsealedSecret(sealed.copyOf(), wasLegacy = false)
    }

    private object EmptySecretStorage : SecretStorage {
        override fun readSealed(): ByteArray? = null

        override fun writeSealed(sealed: ByteArray) = Unit

        override fun clear() = Unit
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
        private const val BIKE_ID = "bike-a"
        private const val OTHER_BIKE_ID = "bike-b"
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BASE_LAT = -33.45
        private const val BASE_LON = -70.66
        private const val LAT_STEP = 0.00001
    }
}
