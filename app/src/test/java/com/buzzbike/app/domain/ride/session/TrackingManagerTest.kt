package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.data.local.dao.OutboxDao
import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.OutboxEntity
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity
import com.buzzbike.app.data.repository.RideRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [TrackingManager]. The sensor, permission check and
 * persistence layer are all faked, so no Android runtime is required.
 */
class TrackingManagerTest {
    @Test
    fun given_noPermission_when_startTracking_thenReturnsFalseAndDoesNotSubscribe() =
        runTest {
            val sensor = FakeLocationSensor(flowOf(reading(1)))
            val dao = RecordingTrackPointDao()
            val manager = manager(sensor = sensor, dao = dao, permission = FakePermissionChecker(false))

            assertFalse(manager.startTracking(RIDE_ID))
            advanceUntilIdle()

            assertEquals(0, sensor.collectionCount)
            assertTrue(dao.inserted.isEmpty())
        }

    @Test
    fun given_permission_when_startTracking_thenReturnsTrueAndPersistsPoints() =
        runTest {
            val dao = RecordingTrackPointDao()
            val manager =
                manager(
                    sensor = FakeLocationSensor(flowOf(reading(1), reading(2))),
                    dao = dao,
                )

            assertTrue(manager.startTracking(RIDE_ID))
            // The manager collects in `backgroundScope`; `advanceUntilIdle()`
            // only drains foreground events, so drive the finite `flowOf`
            // with `runCurrent()` instead.
            runCurrent()

            assertEquals(
                listOf(NANOS_PER_SECOND, 2 * NANOS_PER_SECOND),
                dao.inserted.map { it.timestampNanos },
            )
            assertTrue(dao.inserted.all { it.rideId == RIDE_ID })
            assertEquals(2, manager.trackPoints.first().size)
        }

    @Test
    fun given_blankRideId_when_startTracking_thenReturnsFalse() =
        runTest {
            val sensor = FakeLocationSensor(flowOf(reading(1)))
            val manager = manager(sensor = sensor)

            assertFalse(manager.startTracking("   "))
            advanceUntilIdle()

            assertEquals(0, sensor.collectionCount)
        }

    @Test
    fun given_invalidCoordinates_when_readingsArrive_thenInvalidPointsAreSkipped() =
        runTest {
            val dao = RecordingTrackPointDao()
            val manager =
                manager(
                    sensor =
                        FakeLocationSensor(
                            flowOf(
                                reading(1, lat = Double.NaN),
                                reading(2, lat = 91.0),
                                reading(3),
                            ),
                        ),
                    dao = dao,
                )

            assertTrue(manager.startTracking(RIDE_ID))
            runCurrent()

            assertEquals(1, dao.inserted.size)
            assertEquals(3 * NANOS_PER_SECOND, dao.inserted.single().timestampNanos)
        }

    @Test
    fun given_activeTracking_when_startTrackingAgain_thenDoesNotDuplicateSubscription() =
        runTest {
            val sensor = FakeLocationSensor(MutableSharedFlow())
            val manager = manager(sensor = sensor)

            assertTrue(manager.startTracking(RIDE_ID))
            runCurrent()
            assertTrue(manager.startTracking(RIDE_ID))
            runCurrent()

            assertEquals(1, sensor.collectionCount)
        }

    @Test
    fun given_activeTracking_when_stopTracking_thenNoFurtherPointsArePersisted() =
        runTest {
            val readings = MutableSharedFlow<LocationReading>(extraBufferCapacity = 8)
            val dao = RecordingTrackPointDao()
            val manager = manager(sensor = FakeLocationSensor(readings), dao = dao)

            assertTrue(manager.startTracking(RIDE_ID))
            runCurrent()
            readings.emit(reading(1))
            runCurrent()
            assertEquals(1, dao.inserted.size)

            manager.stopTracking()
            runCurrent()
            readings.emit(reading(2))
            runCurrent()

            assertEquals(1, dao.inserted.size)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun TestScope.manager(
        sensor: LocationSensorPort,
        dao: TrackPointDao = RecordingTrackPointDao(),
        permission: LocationPermissionChecker = FakePermissionChecker(true),
    ): TrackingManager =
        TrackingManager(
            locationSensor = sensor,
            trackPointWriter = RideRepository(FakeRideDao(), dao, FakeOutboxDao()),
            permissionChecker = permission,
            scope = backgroundScope,
        )

    private fun reading(
        seconds: Long,
        lat: Double = BASE_LAT,
        lon: Double = BASE_LON,
    ): LocationReading =
        LocationReading(
            lat = lat,
            lon = lon,
            timestampNanos = seconds * NANOS_PER_SECOND,
            altitudeEllipsoidM = 500.0,
            accuracyH = 3.0,
            accuracyV = 4.0,
            speedMps = 5.0,
            bearing = 90f,
        )

    private class FakeLocationSensor(
        private val readings: Flow<LocationReading>,
    ) : LocationSensorPort {
        var collectionCount: Int = 0
            private set

        override fun locationUpdates(): Flow<LocationReading> = readings.onStart { collectionCount++ }
    }

    private class FakePermissionChecker(
        private val granted: Boolean,
    ) : LocationPermissionChecker {
        override fun hasFineLocationPermission(): Boolean = granted
    }

    private class FakeRideDao : RideDao {
        override fun getAllRides(): Flow<List<RideEntity>> = flowOf(emptyList())

        override suspend fun getRideById(id: String): RideEntity? = null

        override fun getActiveRide(): Flow<RideEntity?> = flowOf(null)

        override suspend fun insertRide(ride: RideEntity) = Unit

        override suspend fun updateRide(ride: RideEntity) = Unit

        override suspend fun deactivateAllRides() = Unit

        override suspend fun deleteRide(id: String) = Unit
    }

    private class RecordingTrackPointDao : TrackPointDao {
        val inserted = mutableListOf<TrackPointEntity>()

        override suspend fun insertPoint(point: TrackPointEntity) {
            inserted += point
        }

        override suspend fun insertPoints(points: List<TrackPointEntity>) {
            inserted += points
        }

        override fun getPointsForRide(rideId: String): Flow<List<TrackPointEntity>> = flowOf(emptyList())

        override suspend fun getPointsForRideSync(rideId: String): List<TrackPointEntity> =
            inserted.filter { it.rideId == rideId }

        override suspend fun deletePointsForRide(rideId: String) {
            inserted.removeAll { it.rideId == rideId }
        }
    }

    private class FakeOutboxDao : OutboxDao {
        override suspend fun insert(outbox: OutboxEntity) = Unit

        override suspend fun insertIfAbsent(outbox: OutboxEntity): Long = 1L

        override suspend fun getPendingOutbox(
            now: Long,
            limit: Int,
        ): List<OutboxEntity> = emptyList()

        override suspend fun markSent(
            id: String,
            now: Long,
        ) = Unit

        override suspend fun markFailed(
            id: String,
            error: String,
            now: Long,
            nextAttempt: Long,
        ) = Unit

        override suspend fun deleteSent() = Unit

        override suspend fun countPending(): Int = 0
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BASE_LAT = -33.4489
        private const val BASE_LON = -70.6693
    }
}
