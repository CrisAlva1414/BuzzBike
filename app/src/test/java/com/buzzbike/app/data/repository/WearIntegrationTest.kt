package com.buzzbike.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.data.local.AppDatabase
import com.buzzbike.app.data.local.dao.BikeDao
import com.buzzbike.app.data.local.dao.ComponentDao
import com.buzzbike.app.data.local.dao.ComponentEventDao
import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.RideUsageDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.wear.BikeRepository
import com.buzzbike.app.domain.wear.ComponentRepository
import com.buzzbike.app.domain.wear.ComponentWear
import com.buzzbike.app.domain.wear.NewComponent
import com.buzzbike.app.domain.wear.WearAxis
import com.buzzbike.app.domain.wear.WearClock
import com.buzzbike.app.domain.wear.WearLifecycle
import com.buzzbike.app.domain.wear.WearStatus
import com.buzzbike.app.domain.wear.WearType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end wear accumulation through the real ride writer and Room.
 *
 * A ride started/stopped through [RideRepository] must land in `rides.bike_id`
 * and be the only source of a bike's km. This pins the integration the domain
 * and DAO tests cannot cover separately: start assignment, descriptor update,
 * wear sum and the `logService` baseline reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WearIntegrationTest {
    private var nowMillis = INSTALL_MILLIS
    private lateinit var database: AppDatabase
    private lateinit var bikeDao: BikeDao
    private lateinit var componentDao: ComponentDao
    private lateinit var eventDao: ComponentEventDao
    private lateinit var rideDao: RideDao
    private lateinit var rideUsageDao: RideUsageDao
    private lateinit var trackPointDao: TrackPointDao
    private lateinit var rideRepository: RideRepository
    private lateinit var bikeRepository: BikeRepository
    private lateinit var componentRepository: ComponentRepository

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        bikeDao = database.bikeDao()
        componentDao = database.componentDao()
        eventDao = database.componentEventDao()
        rideDao = database.rideDao()
        rideUsageDao = database.rideUsageDao()
        trackPointDao = database.trackPointDao()
        rideRepository = RideRepository(rideDao, trackPointDao)
        val clock = WearClock { nowMillis }
        bikeRepository = RoomBikeRepository(bikeDao, rideUsageDao, clock)
        componentRepository = RoomComponentRepository(bikeDao, componentDao, eventDao, rideUsageDao, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun given_chainOnBikeA_when_100kmRideIsSaved_thenUsageAndRatioComeFromThatRide() =
        runTest {
            val bikeId = bikeWithChain()

            saveRide(bikeId = bikeId, distanceM = HUNDRED_KM_METERS)
            nowMillis = INSTALL_MILLIS + MILLIS_PER_DAY

            val wear = componentRepository.assessWear(bikeId).single()

            assertEquals(100.0, wear.usage.km, 1e-9)
            assertEquals(0.05, requireNotNull(wear.assessment.ratio), 1e-9)
            assertEquals(WearStatus.OK, wear.assessment.status)
            assertEquals(WearAxis.KM, wear.assessment.limitingAxis)
        }

    @Test
    fun given_assignedAndUnassignedRides_when_assessed_thenOnlyTheAssignedRideAccumulates() =
        runTest {
            val bikeId = bikeWithChain()

            saveRide(bikeId = bikeId, distanceM = HUNDRED_KM_METERS)
            saveRide(bikeId = null, distanceM = FIFTY_KM_METERS)
            nowMillis = INSTALL_MILLIS + MILLIS_PER_DAY

            val wear = componentRepository.assessWear(bikeId).single()

            assertEquals(100.0, wear.usage.km, 1e-9)
            assertEquals(0.05, requireNotNull(wear.assessment.ratio), 1e-9)
        }

    @Test
    fun given_acumulatedWear_when_logService_thenAccumulationResetsToZero() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            val componentId =
                componentRepository.createComponent(
                    NewComponent(
                        bikeId = bikeId,
                        type = WearType.CHAIN,
                        label = "Chain",
                        lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                        installedAtMillis = INSTALL_MILLIS,
                    ),
                )
            saveRide(bikeId = bikeId, distanceM = HUNDRED_KM_METERS)
            nowMillis = INSTALL_MILLIS + MILLIS_PER_DAY
            assertEquals(
                100.0,
                componentRepository
                    .assessWear(bikeId)
                    .single()
                    .usage.km,
                1e-9,
            )

            // The service baseline must be after every ride already saved.
            // Rides are pinned to RIDE_START_MILLIS, so this is deterministic
            // and never depends on the wall clock.
            nowMillis = SERVICE_BASELINE_MILLIS
            componentRepository.logService(
                componentId = componentId,
                consumedKm = 100.0,
                consumedHours = 0.0,
                wearRatio = 0.05,
            )

            val wear = componentRepository.assessWear(bikeId).single()

            assertEquals(0.0, wear.usage.km, 0.0)
            assertEquals(0.0, requireNotNull(wear.assessment.ratio), 0.0)
        }

    @Test
    fun given_bikeWithChainAndAssignedRide_when_bikeIsDeleted_then_rideKeepsBikeIdAndWearIsEmpty() =
        runTest {
            val bikeId = bikeWithChain()
            val rideId = saveRide(bikeId = bikeId, distanceM = HUNDRED_KM_METERS)
            nowMillis = INSTALL_MILLIS + MILLIS_PER_DAY

            bikeRepository.deleteBike(bikeId)

            assertNull("the bike row must be gone", bikeRepository.getBike(bikeId))
            val ride = requireNotNull(rideDao.getRideById(rideId)) { "the historical ride must survive" }
            assertEquals("the ride keeps the historical bike reference", bikeId, ride.bikeId)
            assertEquals(emptyList<ComponentWear>(), componentRepository.assessWear(bikeId))
        }

    private suspend fun bikeWithChain(): String {
        val bikeId = bikeRepository.createBike("Gravel")
        componentRepository.createComponent(
            NewComponent(
                bikeId = bikeId,
                type = WearType.CHAIN,
                label = "Chain",
                lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                installedAtMillis = INSTALL_MILLIS,
            ),
        )
        return bikeId
    }

    private suspend fun saveRide(
        bikeId: String?,
        distanceM: Double,
    ): String {
        val rideId = rideRepository.startRide(bikeId = bikeId)
        rideRepository.stopRide(rideId)
        rideRepository.saveRideDescriptors(
            rideId = rideId,
            descriptors = descriptors(distanceM),
        )
        // `startRide` stamps the wall clock; pin the start so wear windows and
        // service baselines are reproducible regardless of when the test runs.
        val stored = requireNotNull(rideDao.getRideById(rideId))
        rideDao.updateRide(stored.copy(startTimeMillis = RIDE_START_MILLIS))
        return rideId
    }

    private fun descriptors(distanceM: Double): RideDescriptors =
        RideDescriptors(
            distanceM = distanceM,
            elapsedSeconds = 7_200L,
            movingSeconds = 7_200L,
            stoppedSeconds = 0L,
            avgMovingSpeedMps = distanceM / 7_200.0,
            avgSpeedMps = distanceM / 7_200.0,
            maxSpeedMps = distanceM / 7_200.0,
            stopCount = 0,
            stopDurationsSeconds = emptyList(),
            acceptedPoints = 10,
            rejectedPoints = 0,
            gapCount = 0,
            distanceIsLowerBound = false,
            modelVersion = "test",
        )

    private companion object {
        const val INSTALL_MILLIS = 1_700_000_000_000L
        const val MILLIS_PER_DAY = 86_400_000L
        const val RIDE_START_MILLIS = INSTALL_MILLIS
        const val SERVICE_BASELINE_MILLIS = INSTALL_MILLIS + MILLIS_PER_DAY
        const val CHAIN_KM = 2000.0
        const val HUNDRED_KM_METERS = 100_000.0
        const val FIFTY_KM_METERS = 50_000.0
    }
}
