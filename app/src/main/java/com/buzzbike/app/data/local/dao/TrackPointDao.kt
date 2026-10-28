package com.buzzbike.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.buzzbike.app.data.local.entity.TrackPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: TrackPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY timestampNanos ASC")
    fun getPointsForRide(rideId: String): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY timestampNanos ASC")
    suspend fun getPointsForRideSync(rideId: String): List<TrackPointEntity>

    @Query("DELETE FROM track_points WHERE rideId = :rideId")
    suspend fun deletePointsForRide(rideId: String)
}
