package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.TrackPointEntity
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.sync.RideSyncDataSource
import com.buzzbike.app.domain.sync.RideSyncInput
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [RideSyncDataSource].
 *
 * Reads the finished ride and its persisted track points and maps them to the
 * pure domain [RideSyncInput]. This is the only place where the sync payload
 * input meets the database schema.
 *
 * `RideEntity` does not persist `stopCount`, `gapCount` or `rejectedPoints`,
 * so those descriptors are sent as `0`/empty until the columns are added. The
 * data minimization itself happens later in
 * [com.buzzbike.app.domain.sync.RidePayloadBuilder], which decimates points.
 */
@Singleton
class RideSyncDataSourceImpl
    @Inject
    constructor(
        private val rideDao: RideDao,
        private val trackPointDao: TrackPointDao,
    ) : RideSyncDataSource {
        override suspend fun load(rideId: String): RideSyncInput? {
            val ride = rideDao.getRideById(rideId) ?: return null
            val points = trackPointDao.getPointsForRideSync(rideId)
            val endTimeMillis = ride.endTimeMillis
            val elapsedSeconds =
                endTimeMillis
                    ?.let { ((it - ride.startTimeMillis) / MILLIS_PER_SECOND).coerceAtLeast(0L) }
                    ?: 0L

            return RideSyncInput(
                rideId = ride.id,
                startTimeMillis = ride.startTimeMillis,
                endTimeMillis = endTimeMillis,
                descriptors =
                    RideDescriptors(
                        distanceM = ride.distanceM,
                        elapsedSeconds = elapsedSeconds,
                        movingSeconds = ride.movingTimeSeconds,
                        stoppedSeconds = ride.stoppedTimeSeconds,
                        avgMovingSpeedMps = ride.avgSpeedMps,
                        avgSpeedMps = ride.avgSpeedMps,
                        maxSpeedMps = ride.maxSpeedMps,
                        stopCount = NOT_PERSISTED_COUNT,
                        stopDurationsSeconds = emptyList(),
                        acceptedPoints = points.size,
                        rejectedPoints = NOT_PERSISTED_COUNT,
                        gapCount = NOT_PERSISTED_COUNT,
                        distanceIsLowerBound = false,
                        modelVersion = MODEL_VERSION,
                    ),
                elevationGainM = ride.elevationGainM,
                elevationLossM = ride.elevationLossM,
                caloriesKcal = ride.caloriesKcal,
                caloriesLowKcal = ride.caloriesLowKcal,
                caloriesHighKcal = ride.caloriesHighKcal,
                calorieModel = ride.calorieModel,
                maxSpeedMps = ride.maxSpeedMps,
                points = points.map(TrackPointEntity::toTrackPoint),
            )
        }

        private companion object {
            const val MILLIS_PER_SECOND = 1_000L
            const val NOT_PERSISTED_COUNT = 0
            const val MODEL_VERSION = "v1"
        }
    }

internal fun TrackPointEntity.toTrackPoint(): TrackPoint =
    TrackPoint(
        timestampMonotonicNanos = timestampNanos,
        lat = lat,
        lon = lon,
        altitudeMslM = altitudeMslM,
        altitudeEllipsoidM = altitudeEllipsoidM,
        accuracyH = accuracyH,
        accuracyV = accuracyV,
        speedMps = speedMps,
        bearing = bearing,
        pressureHpa = pressureHpa,
    )
