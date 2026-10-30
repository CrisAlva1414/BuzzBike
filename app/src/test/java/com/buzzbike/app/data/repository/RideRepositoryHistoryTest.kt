package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM test for the history read model exposed by [RideRepository]. */
class RideRepositoryHistoryTest {
    @Test
    fun given_activeAndCompletedRides_when_observeCompletedRides_then_activeIsExcluded() =
        runTest {
            val rides =
                listOf(
                    ride("active", isActive = true),
                    ride("done-1", isActive = false),
                    ride("done-2", isActive = false),
                )
            val repository = RideRepository(FakeRideDao(rides), FakeTrackPointDao())

            val records = repository.observeCompletedRides().first()

            assertEquals(listOf("done-1", "done-2"), records.map { it.id })
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

    private class FakeTrackPointDao : TrackPointDao {
        override suspend fun insertPoint(point: TrackPointEntity) = Unit

        override suspend fun insertPoints(points: List<TrackPointEntity>) = Unit

        override fun getPointsForRide(rideId: String): Flow<List<TrackPointEntity>> = emptyFlow()

        override suspend fun getPointsForRideSync(rideId: String): List<TrackPointEntity> = emptyList()

        override suspend fun deletePointsForRide(rideId: String) = Unit
    }
}
