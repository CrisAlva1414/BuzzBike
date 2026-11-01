package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity
import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the [RideRepository] implementation of the session port.
 *
 * The DAOs are faked (Room itself is covered by the DAO/migration tests), so
 * this verifies the lifecycle wiring the session layer depends on: active ride
 * lookup for recovery and orphan-free discards.
 */
class RideRepositorySessionWriterTest {
    @Test
    fun given_activeRide_when_findActiveRideId_thenReturnsDaoValue() =
        runTest {
            val repository = RideRepository(FakeRideDao(activeRideId = RIDE_ID), FakeTrackPointDao())

            assertEquals(RIDE_ID, repository.findActiveRideId())
        }

    @Test
    fun given_noActiveRide_when_findActiveRideId_thenNull() =
        runTest {
            val repository = RideRepository(FakeRideDao(), FakeTrackPointDao())

            assertNull(repository.findActiveRideId())
        }

    @Test
    fun given_rideWithPoints_when_deleteRide_thenPointsAndRideAreBothDeleted() =
        runTest {
            val log = mutableListOf<String>()
            val repository =
                RideRepository(
                    FakeRideDao(log = log),
                    FakeTrackPointDao(log = log),
                )

            repository.deleteRide(RIDE_ID)

            // Points first so a discard never leaves orphan track_points.
            assertEquals(listOf("points:$RIDE_ID", "ride:$RIDE_ID"), log)
        }

    @Test
    fun given_persistedPoints_when_loadTrackPoints_thenMapsEveryField() =
        runTest {
            val entity =
                TrackPointEntity(
                    id = "point-1",
                    rideId = RIDE_ID,
                    timestampNanos = 123_456_789L,
                    lat = -33.45,
                    lon = -70.66,
                    altitudeMslM = 550.5,
                    altitudeEllipsoidM = 574.2,
                    accuracyH = 4.5,
                    accuracyV = 6.5,
                    speedMps = 7.25,
                    bearing = 180.5f,
                    pressureHpa = 1013.2,
                )
            val repository = RideRepository(FakeRideDao(), FakeTrackPointDao(points = listOf(entity)))

            val points = repository.loadTrackPoints(RIDE_ID)

            assertEquals(
                listOf(
                    TrackPoint(
                        timestampMonotonicNanos = 123_456_789L,
                        lat = -33.45,
                        lon = -70.66,
                        altitudeMslM = 550.5,
                        altitudeEllipsoidM = 574.2,
                        accuracyH = 4.5,
                        accuracyV = 6.5,
                        speedMps = 7.25,
                        bearing = 180.5f,
                        pressureHpa = 1013.2,
                    ),
                ),
                points,
            )
        }

    @Test
    fun given_previousRide_when_startRide_thenDeactivatesAndInsertsNewActiveRow() =
        runTest {
            val rideDao = FakeRideDao()
            val repository = RideRepository(rideDao, FakeTrackPointDao())

            val rideId = repository.startRide()

            assertEquals(1, rideDao.deactivateCalls)
            assertEquals(1, rideDao.inserted.size)
            assertEquals(rideId, rideDao.inserted.single().id)
            assertTrue(rideDao.inserted.single().isActive)
        }

    @Test
    fun given_completeProfile_when_startRide_thenInsertCarriesSnapshotColumns() =
        runTest {
            val rideDao = FakeRideDao()
            val repository = RideRepository(rideDao, FakeTrackPointDao())
            val profile = UserProfile(weightKg = 72.5, heightCm = 178.0, ageYears = 34, isMale = true)

            repository.startRide(profile)

            val inserted = rideDao.inserted.single()
            assertEquals(72.5, inserted.profileWeightKg ?: Double.NaN, 0.0)
            assertEquals(178.0, inserted.profileHeightCm ?: Double.NaN, 0.0)
            assertEquals(34, inserted.profileAgeYears)
            assertEquals(true, inserted.profileIsMale)
        }

    @Test
    fun given_noProfile_when_startRide_thenInsertSnapshotColumnsStayNull() =
        runTest {
            val rideDao = FakeRideDao()
            val repository = RideRepository(rideDao, FakeTrackPointDao())

            repository.startRide(null)

            val inserted = rideDao.inserted.single()
            assertNull(inserted.profileWeightKg)
            assertNull(inserted.profileHeightCm)
            assertNull(inserted.profileAgeYears)
            assertNull(inserted.profileIsMale)
        }

    @Test
    fun given_bikeId_when_startRide_thenInsertCarriesTheAssignment() =
        runTest {
            val rideDao = FakeRideDao()
            val repository = RideRepository(rideDao, FakeTrackPointDao())

            repository.startRide(bikeId = BIKE_ID)

            assertEquals(BIKE_ID, rideDao.inserted.single().bikeId)
        }

    @Test
    fun given_noBike_when_startRide_thenInsertBikeIdIsNull() =
        runTest {
            val rideDao = FakeRideDao()
            val repository = RideRepository(rideDao, FakeTrackPointDao())

            repository.startRide(bikeId = null)

            assertNull(rideDao.inserted.single().bikeId)
        }

    @Test
    fun given_storedSnapshot_when_persistedProfile_thenReturnsIt() =
        runTest {
            val ride =
                RideEntity(
                    id = RIDE_ID,
                    startTimeMillis = 1_700_000_000_000L,
                    profileWeightKg = 72.5,
                    profileHeightCm = 178.0,
                    profileAgeYears = 34,
                    profileIsMale = true,
                )
            val repository = RideRepository(FakeRideDao(storedRide = ride), FakeTrackPointDao())

            assertEquals(
                UserProfile(weightKg = 72.5, heightCm = 178.0, ageYears = 34, isMale = true),
                repository.persistedProfile(RIDE_ID),
            )
            assertNull(repository.persistedProfile("missing"))
        }

    @Test
    fun given_partialProfile_when_saveRideDescriptors_thenStoresOnlyKnownSnapshotFields() =
        runTest {
            val ride = RideEntity(id = RIDE_ID, startTimeMillis = 1_700_000_000_000L, isActive = true)
            val rideDao = FakeRideDao(storedRide = ride)
            val repository = RideRepository(rideDao, FakeTrackPointDao())

            repository.saveRideDescriptors(
                rideId = RIDE_ID,
                descriptors = descriptors(),
                elevationGainM = 10.0,
                elevationLossM = 5.0,
                caloriesKcal = 100.0,
                caloriesLowKcal = 70.0,
                caloriesHighKcal = 130.0,
                calorieModel = "COMPENDIUM_VELOCITY",
                profile = UserProfile(weightKg = 72.5, ageYears = 34),
            )

            val updated = rideDao.updated.single()
            assertEquals(72.5, updated.profileWeightKg ?: Double.NaN, 0.0)
            assertNull(updated.profileHeightCm)
            assertEquals(34, updated.profileAgeYears)
            assertNull(updated.profileIsMale)
        }

    private fun descriptors(): RideDescriptors =
        RideDescriptors(
            distanceM = 1_000.0,
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

    private class FakeRideDao(
        private val activeRideId: String? = null,
        private val log: MutableList<String>? = null,
        private val storedRide: RideEntity? = null,
    ) : RideDao {
        var deactivateCalls: Int = 0
            private set

        val inserted = mutableListOf<RideEntity>()

        val updated = mutableListOf<RideEntity>()

        override fun getAllRides(): Flow<List<RideEntity>> = emptyFlow()

        override suspend fun getRideById(id: String): RideEntity? = storedRide?.takeIf { it.id == id }

        override fun getActiveRide(): Flow<RideEntity?> = emptyFlow()

        override suspend fun getActiveRideId(): String? = activeRideId

        override suspend fun insertRide(ride: RideEntity) {
            inserted += ride
        }

        override suspend fun updateRide(ride: RideEntity) {
            updated += ride
        }

        override suspend fun deactivateAllRides() {
            deactivateCalls += 1
        }

        override suspend fun deleteRide(id: String) {
            log?.add("ride:$id")
        }
    }

    private class FakeTrackPointDao(
        private val log: MutableList<String>? = null,
        private val points: List<TrackPointEntity> = emptyList(),
    ) : TrackPointDao {
        override suspend fun insertPoint(point: TrackPointEntity) = Unit

        override suspend fun insertPoints(points: List<TrackPointEntity>) = Unit

        override fun getPointsForRide(rideId: String): Flow<List<TrackPointEntity>> = emptyFlow()

        override suspend fun getPointsForRideSync(rideId: String): List<TrackPointEntity> = points

        override suspend fun deletePointsForRide(rideId: String) {
            log?.add("points:$rideId")
        }
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
        private const val BIKE_ID = "bike-a"
    }
}
