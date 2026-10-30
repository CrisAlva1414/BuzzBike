package com.buzzbike.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.buzzbike.app.data.local.entity.RideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startTimeMillis DESC")
    fun getAllRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun getRideById(id: String): RideEntity?

    @Query("SELECT * FROM rides WHERE isActive = 1 LIMIT 1")
    fun getActiveRide(): Flow<RideEntity?>

    @Query("SELECT id FROM rides WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveRideId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideEntity)

    @Update
    suspend fun updateRide(ride: RideEntity)

    @Query("UPDATE rides SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateAllRides()

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun deleteRide(id: String)

    /**
     * Atomically retires every active ride and inserts the new one, so a
     * concurrent double start can never leave two active rows behind.
     */
    @Transaction
    suspend fun replaceActiveRide(ride: RideEntity) {
        deactivateAllRides()
        insertRide(ride)
    }

    /**
     * Atomically deletes the ride and its track points. Both tables live in the
     * same database, so the transaction spans them via the injected DAO.
     */
    @Transaction
    suspend fun deleteRideWithPoints(
        rideId: String,
        trackPointDao: TrackPointDao,
    ) {
        trackPointDao.deletePointsForRide(rideId)
        deleteRide(rideId)
    }
}
