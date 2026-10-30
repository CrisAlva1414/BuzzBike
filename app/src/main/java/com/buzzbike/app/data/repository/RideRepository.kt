package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity
import com.buzzbike.app.domain.history.RideDetail
import com.buzzbike.app.domain.history.RideDetailSource
import com.buzzbike.app.domain.history.RideHistorySource
import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.session.RideSessionWriter
import com.buzzbike.app.domain.ride.session.TrackPointWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Single façade for ride persistence. Outbox writes live in
// RoomOutboxEnqueuer so this class stays focused on ride/track-point storage.
@Suppress("LongParameterList", "TooManyFunctions")
@Singleton
class RideRepository
    @Inject
    constructor(
        private val rideDao: RideDao,
        private val trackPointDao: TrackPointDao,
    ) : TrackPointWriter,
        RideSessionWriter,
        RideHistorySource,
        RideDetailSource {
        fun getAllRides(): Flow<List<RideEntity>> = rideDao.getAllRides()

        override fun observeCompletedRides(): Flow<List<RideRecord>> =
            rideDao.getAllRides().map { rides -> rides.filterNot { it.isActive }.map { it.toRideRecord() } }

        fun getActiveRide(): Flow<RideEntity?> = rideDao.getActiveRide()

        suspend fun getRideById(id: String): RideEntity? = rideDao.getRideById(id)

        override suspend fun getRideDetail(rideId: String): RideDetail? {
            val entity = rideDao.getRideById(rideId) ?: return null
            // The ride still being recorded is not history yet.
            return if (entity.isActive) {
                null
            } else {
                RideDetail(ride = entity.toRideRecord(), points = loadTrackPoints(rideId))
            }
        }

        /**
         * Returns the full persisted ride, including every final descriptor
         * populated by [saveRideDescriptors] once the session is stopped.
         */
        suspend fun getRideWithDescriptors(rideId: String): RideEntity? = rideDao.getRideById(rideId)

        override suspend fun startRide(): String {
            val rideId = UUID.randomUUID().toString()
            val ride =
                RideEntity(
                    id = rideId,
                    startTimeMillis = System.currentTimeMillis(),
                    isActive = true,
                )
            rideDao.replaceActiveRide(ride)
            return rideId
        }

        override suspend fun findActiveRideId(): String? = rideDao.getActiveRideId()

        override suspend fun loadTrackPoints(rideId: String): List<TrackPoint> =
            trackPointDao.getPointsForRideSync(rideId).map(TrackPointEntity::toTrackPoint)

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
            caloriesLowKcal: Double?,
            caloriesHighKcal: Double?,
            calorieModel: String?,
        ) {
            val ride = rideDao.getRideById(rideId) ?: return
            rideDao.updateRide(
                ride.copy(
                    distanceM = descriptors.distanceM,
                    movingTimeSeconds = descriptors.movingSeconds,
                    elevationGainM = elevationGainM,
                    elevationLossM = elevationLossM,
                    caloriesKcal = caloriesKcal,
                    caloriesLowKcal = caloriesLowKcal,
                    caloriesHighKcal = caloriesHighKcal,
                    calorieModel = calorieModel,
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

        override suspend fun deleteRide(rideId: String) {
            rideDao.deleteRideWithPoints(rideId, trackPointDao)
        }
    }
