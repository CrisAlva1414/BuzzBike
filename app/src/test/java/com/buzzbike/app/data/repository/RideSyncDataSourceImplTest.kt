package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [RideSyncDataSourceImpl] using DAO fakes.
 *
 * Room itself is exercised by the existing DAO tests; here only the mapping
 * from persisted rows to the pure [com.buzzbike.app.domain.sync.RideSyncInput]
 * matters, so no Robolectric is needed.
 */
class RideSyncDataSourceImplTest {
    @Test
    fun given_rideAndPoints_when_load_thenMapsDescriptorsAndPoints() =
        runTest {
            val trackPointDao = FakeTrackPointDao(listOf(POINT, SECOND_POINT))
            val dataSource = RideSyncDataSourceImpl(FakeRideDao(RIDE), trackPointDao)

            val input = dataSource.load(RIDE_ID)

            requireNotNull(input)
            assertEquals(RIDE_ID, input.rideId)
            assertEquals(START_MILLIS, input.startTimeMillis)
            assertEquals(START_MILLIS + DURATION_MILLIS, input.endTimeMillis)
            assertEquals(DURATION_SECONDS, input.descriptors.elapsedSeconds)
            assertEquals(540L, input.descriptors.movingSeconds)
            assertEquals(60L, input.descriptors.stoppedSeconds)
            assertEquals(6.5, input.descriptors.avgSpeedMps, DELTA)
            assertEquals(9.5, input.descriptors.maxSpeedMps, DELTA)
            assertEquals(0, input.descriptors.stopCount)
            assertEquals(2, input.descriptors.acceptedPoints)
            assertEquals(0, input.descriptors.rejectedPoints)
            assertEquals(0, input.descriptors.gapCount)
            assertEquals("v1", input.descriptors.modelVersion)
            assertEquals(12.5, input.elevationGainM, DELTA)
            assertEquals(4.5, input.elevationLossM, DELTA)
            assertEquals(320.0, input.caloriesKcal)
            assertEquals(2, input.points.size)

            val point = input.points.first()
            assertEquals(TIMESTAMP_NANOS, point.timestampMonotonicNanos)
            assertEquals(LAT, point.lat, DELTA)
            assertEquals(LON, point.lon, DELTA)
            assertEquals(540.0, point.altitudeMslM)
            assertEquals(541.0, point.altitudeEllipsoidM)
            assertEquals(3.0, point.accuracyH)
            assertEquals(5.0, point.accuracyV)
            assertEquals(6.0, point.speedMps)
            assertEquals(180.5f, point.bearing)
            assertEquals(1013.2, point.pressureHpa)
        }

    @Test
    fun given_missingRide_when_load_thenReturnsNullAndSkipsPoints() =
        runTest {
            val trackPointDao = FakeTrackPointDao(listOf(POINT))
            val dataSource = RideSyncDataSourceImpl(FakeRideDao(null), trackPointDao)

            val input = dataSource.load(RIDE_ID)

            assertNull(input)
            assertEquals(0, trackPointDao.syncCalls)
        }

    @Test
    fun given_rideWithoutEndTime_when_load_thenElapsedIsZero() =
        runTest {
            val dataSource =
                RideSyncDataSourceImpl(
                    FakeRideDao(RIDE.copy(endTimeMillis = null)),
                    FakeTrackPointDao(emptyList()),
                )

            val input = dataSource.load(RIDE_ID)

            requireNotNull(input)
            assertEquals(0L, input.descriptors.elapsedSeconds)
            assertNull(input.endTimeMillis)
            assertTrue(input.points.isEmpty())
        }

    private class FakeRideDao(
        private val ride: RideEntity?,
    ) : RideDao {
        override fun getAllRides(): Flow<List<RideEntity>> = emptyFlow()

        override suspend fun getRideById(id: String): RideEntity? = ride

        override fun getActiveRide(): Flow<RideEntity?> = emptyFlow()

        override suspend fun insertRide(ride: RideEntity) = Unit

        override suspend fun updateRide(ride: RideEntity) = Unit

        override suspend fun deactivateAllRides() = Unit

        override suspend fun deleteRide(id: String) = Unit
    }

    private class FakeTrackPointDao(
        private val points: List<TrackPointEntity>,
    ) : TrackPointDao {
        var syncCalls: Int = 0
            private set

        override suspend fun insertPoint(point: TrackPointEntity) = Unit

        override suspend fun insertPoints(points: List<TrackPointEntity>) = Unit

        override fun getPointsForRide(rideId: String): Flow<List<TrackPointEntity>> = emptyFlow()

        override suspend fun getPointsForRideSync(rideId: String): List<TrackPointEntity> {
            syncCalls += 1
            return points
        }

        override suspend fun deletePointsForRide(rideId: String) = Unit
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
        private const val START_MILLIS = 1_700_000_000_000L
        private const val DURATION_MILLIS = 600_000L
        private const val DURATION_SECONDS = 600L
        private const val TIMESTAMP_NANOS = 5_000_000_000L
        private const val LAT = -33.4
        private const val LON = -70.6
        private const val DELTA = 1e-9
        private const val SECOND_TIMESTAMP_NANOS = 5_010_000_000L

        private val RIDE =
            RideEntity(
                id = RIDE_ID,
                startTimeMillis = START_MILLIS,
                endTimeMillis = START_MILLIS + DURATION_MILLIS,
                distanceM = 1_234.5,
                movingTimeSeconds = 540L,
                stoppedTimeSeconds = 60L,
                elevationGainM = 12.5,
                elevationLossM = 4.5,
                caloriesKcal = 320.0,
                avgSpeedMps = 6.5,
                maxSpeedMps = 9.5,
            )

        private val POINT =
            TrackPointEntity(
                id = "point-1",
                rideId = RIDE_ID,
                timestampNanos = TIMESTAMP_NANOS,
                lat = LAT,
                lon = LON,
                altitudeMslM = 540.0,
                altitudeEllipsoidM = 541.0,
                accuracyH = 3.0,
                accuracyV = 5.0,
                speedMps = 6.0,
                bearing = 180.5f,
                pressureHpa = 1013.2,
            )

        private val SECOND_POINT =
            POINT.copy(id = "point-2", timestampNanos = SECOND_TIMESTAMP_NANOS)
    }
}
