package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the single-ride detail read in [RideRepository].
 *
 * The track-point DAO is ordered by `timestampNanos ASC`; the fake returns the
 * same order and the test asserts the repository preserves it.
 */
class RideRepositoryDetailTest {
    @Test
    fun given_unknownRide_when_getRideDetail_then_nullAndNoPointQuery() =
        runTest {
            val points = FakeTrackPointDao()
            val repository = RideRepository(FakeRideDao(emptyList()), points)

            assertNull(repository.getRideDetail(MISSING_ID))
            assertEquals(0, points.calls)
        }

    @Test
    fun given_activeRide_when_getRideDetail_then_nullAndNoPointQuery() =
        runTest {
            val points = FakeTrackPointDao()
            val repository = RideRepository(FakeRideDao(listOf(ride(ACTIVE_ID, isActive = true))), points)

            assertNull(repository.getRideDetail(ACTIVE_ID))
            assertEquals(0, points.calls)
        }

    @Test
    fun given_completedRide_when_getRideDetail_then_recordAndPointsInDaoOrder() =
        runTest {
            val points =
                listOf(
                    point(timestampNanos = 1_000L, lat = -33.45, lon = -70.66),
                    point(timestampNanos = 2_000L, lat = -33.46, lon = -70.67),
                )
            val repository =
                RideRepository(
                    FakeRideDao(listOf(ride(COMPLETED_ID, isActive = false))),
                    FakeTrackPointDao(points),
                )

            val detail = requireNotNull(repository.getRideDetail(COMPLETED_ID))

            assertEquals(COMPLETED_ID, detail.ride.id)
            assertEquals(listOf(1_000L, 2_000L), detail.points.map { it.timestampMonotonicNanos })
            assertEquals(listOf(-33.45, -33.46), detail.points.map { it.lat })
        }

    private fun ride(
        id: String,
        isActive: Boolean,
    ): RideEntity =
        RideEntity(
            id = id,
            startTimeMillis = 1_700_000_000_000L,
            isActive = isActive,
        )

    private fun point(
        timestampNanos: Long,
        lat: Double,
        lon: Double,
    ): TrackPointEntity =
        TrackPointEntity(
            id = "p$timestampNanos",
            rideId = COMPLETED_ID,
            timestampNanos = timestampNanos,
            lat = lat,
            lon = lon,
        )

    private class FakeRideDao(
        private val rides: List<RideEntity> = emptyList(),
    ) : RideDao {
        override fun getAllRides(): Flow<List<RideEntity>> = flowOf(rides)

        override suspend fun getRideById(id: String): RideEntity? = rides.firstOrNull { it.id == id }

        override fun getActiveRide(): Flow<RideEntity?> = flowOf(rides.firstOrNull { it.isActive })

        override suspend fun getActiveRideId(): String? = rides.firstOrNull { it.isActive }?.id

        override suspend fun insertRide(ride: RideEntity) = Unit

        override suspend fun updateRide(ride: RideEntity) = Unit

        override suspend fun deactivateAllRides() = Unit

        override suspend fun deleteRide(id: String) = Unit
    }

    private class FakeTrackPointDao(
        private val points: List<TrackPointEntity> = emptyList(),
    ) : TrackPointDao {
        var calls = 0

        override suspend fun insertPoint(point: TrackPointEntity) = Unit

        override suspend fun insertPoints(points: List<TrackPointEntity>) = Unit

        override fun getPointsForRide(rideId: String): Flow<List<TrackPointEntity>> = emptyFlow()

        override suspend fun getPointsForRideSync(rideId: String): List<TrackPointEntity> {
            calls++
            return points
        }

        override suspend fun deletePointsForRide(rideId: String) = Unit
    }

    private companion object {
        const val MISSING_ID = "missing"
        const val ACTIVE_ID = "active"
        const val COMPLETED_ID = "done-1"
    }
}
