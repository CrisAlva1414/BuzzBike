package com.buzzbike.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.data.local.AppDatabase
import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.domain.model.RideDescriptors
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room-backed tests for the bike assignment of a persisted ride.
 *
 * They exercise the real DAOs and the `rides.bike_id` column, so a mapper or
 * schema regression that silently drops the assignment fails here instead of
 * only in an instrumented test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RideRepositoryBikePersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var rideDao: RideDao
    private lateinit var trackPointDao: TrackPointDao
    private lateinit var repository: RideRepository

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        rideDao = database.rideDao()
        trackPointDao = database.trackPointDao()
        repository = RideRepository(rideDao, trackPointDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun given_bike_when_startRide_thenRowKeepsTheAssignment() =
        runTest {
            val rideId = repository.startRide(bikeId = BIKE_A)

            assertEquals(BIKE_A, requireNotNull(rideDao.getRideById(rideId)).bikeId)
        }

    @Test
    fun given_noBike_when_startRide_thenRowBikeIdIsNull() =
        runTest {
            val rideId = repository.startRide(bikeId = null)

            assertNull(requireNotNull(rideDao.getRideById(rideId)).bikeId)
        }

    @Test
    fun given_bike_when_stopAndSaveDescriptors_thenBikeIdIsRetained() =
        runTest {
            val rideId = repository.startRide(bikeId = BIKE_A)

            repository.stopRide(rideId)
            repository.saveRideDescriptors(
                rideId = rideId,
                descriptors = descriptors(distanceM = 12_345.0),
                elevationGainM = 10.0,
                elevationLossM = 5.0,
            )

            val row = requireNotNull(rideDao.getRideById(rideId))
            assertFalse(row.isActive)
            assertEquals(BIKE_A, row.bikeId)
            assertEquals(12_345.0, row.distanceM, 0.0)
        }

    @Test
    fun given_startedBikeRide_when_garageSelectionChanges_thenRecoveryReadsThePersistedAssignment() =
        runTest {
            val firstRideId = repository.startRide(bikeId = BIKE_A)
            repository.stopRide(firstRideId)
            repository.saveRideDescriptors(firstRideId, descriptors())

            // A later ride with another bike simulates the user changing the
            // live garage selection. It must not rewrite the first ride.
            val secondRideId = repository.startRide(bikeId = BIKE_B)

            assertEquals(secondRideId, repository.findActiveRideId())
            assertEquals(BIKE_A, requireNotNull(repository.getRideDetail(firstRideId)).ride.bikeId)
            assertEquals(BIKE_B, requireNotNull(rideDao.getRideById(secondRideId)).bikeId)
        }

    private fun descriptors(distanceM: Double = 1_000.0): RideDescriptors =
        RideDescriptors(
            distanceM = distanceM,
            elapsedSeconds = 600L,
            movingSeconds = 600L,
            stoppedSeconds = 0L,
            avgMovingSpeedMps = 1.6,
            avgSpeedMps = 1.6,
            maxSpeedMps = 3.2,
            stopCount = 0,
            stopDurationsSeconds = emptyList(),
            acceptedPoints = 10,
            rejectedPoints = 0,
            gapCount = 0,
            distanceIsLowerBound = false,
            modelVersion = "test",
        )

    private companion object {
        const val BIKE_A = "bike-a"
        const val BIKE_B = "bike-b"
    }
}
