package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.OutboxDao
import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.OutboxEntity
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.session.RideSessionWriter
import com.buzzbike.app.domain.ride.session.TrackPointWriter
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// This is the single façade for ride persistence; the outbox method pushes
// it to 11 functions (detekt threshold: 11). Splitting the class is a larger
// refactor deferred to the sync slice.
@Suppress("TooManyFunctions")
@Singleton
class RideRepository
    @Inject
    constructor(
        private val rideDao: RideDao,
        private val trackPointDao: TrackPointDao,
        private val outboxDao: OutboxDao,
    ) : TrackPointWriter,
        RideSessionWriter {
        fun getAllRides(): Flow<List<RideEntity>> = rideDao.getAllRides()

        fun getActiveRide(): Flow<RideEntity?> = rideDao.getActiveRide()

        suspend fun getRideById(id: String): RideEntity? = rideDao.getRideById(id)

        /**
         * Returns the full persisted ride, including every final descriptor
         * populated by [saveRideDescriptors] once the session is stopped.
         */
        suspend fun getRideWithDescriptors(rideId: String): RideEntity? = rideDao.getRideById(rideId)

        override suspend fun startRide(): String {
            rideDao.deactivateAllRides()
            val rideId = UUID.randomUUID().toString()
            val ride =
                RideEntity(
                    id = rideId,
                    startTimeMillis = System.currentTimeMillis(),
                    isActive = true,
                )
            rideDao.insertRide(ride)
            return rideId
        }

        override suspend fun stopRide(rideId: String) {
            val ride = rideDao.getRideById(rideId) ?: return
            rideDao.updateRide(
                ride.copy(
                    endTimeMillis = System.currentTimeMillis(),
                    isActive = false,
                ),
            )
        }

        override suspend fun saveRideDescriptors(
            rideId: String,
            descriptors: RideDescriptors,
            elevationGainM: Double,
            elevationLossM: Double,
            caloriesKcal: Double?,
        ) {
            val ride = rideDao.getRideById(rideId) ?: return
            rideDao.updateRide(
                ride.copy(
                    distanceM = descriptors.distanceM,
                    movingTimeSeconds = descriptors.movingSeconds,
                    elevationGainM = elevationGainM,
                    elevationLossM = elevationLossM,
                    caloriesKcal = caloriesKcal,
                    avgSpeedMps = descriptors.avgSpeedMps,
                    maxSpeedMps = descriptors.maxSpeedMps,
                ),
            )
        }

        override suspend fun saveTrackPoint(
            rideId: String,
            point: TrackPoint,
        ) {
            val entity =
                TrackPointEntity(
                    id = UUID.randomUUID().toString(),
                    rideId = rideId,
                    timestampNanos = point.timestampMonotonicNanos,
                    lat = point.lat,
                    lon = point.lon,
                    altitudeMslM = point.altitudeMslM,
                    altitudeEllipsoidM = point.altitudeEllipsoidM,
                    accuracyH = point.accuracyH,
                    accuracyV = point.accuracyV,
                    speedMps = point.speedMps,
                    bearing = point.bearing,
                    pressureHpa = point.pressureHpa,
                )
            trackPointDao.insertPoint(entity)
        }

        suspend fun getTrackPoints(rideId: String): List<TrackPointEntity> = trackPointDao.getPointsForRideSync(rideId)

        suspend fun deleteRide(rideId: String) {
            trackPointDao.deletePointsForRide(rideId)
            rideDao.deleteRide(rideId)
        }

        /**
         * Queues a serialized ride for later delivery to Hermes.
         *
         * The row starts as [OutboxEntity.STATUS_PENDING] and immediately
         * eligible for the next retry pass. The caller owns the payload
         * format; this layer treats it as an opaque blob.
         */
        suspend fun enqueueOutbox(
            rideId: String,
            payload: ByteArray,
        ) {
            val now = System.currentTimeMillis()
            outboxDao.insert(
                OutboxEntity(
                    id = UUID.randomUUID().toString(),
                    rideId = rideId,
                    payload = payload,
                    status = OutboxEntity.STATUS_PENDING,
                    createdAt = now,
                    updatedAt = now,
                    nextAttemptAt = now,
                ),
            )
        }
    }
