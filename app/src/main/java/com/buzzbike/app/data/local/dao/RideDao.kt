package com.buzzbike.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideEntity)

    @Update
    suspend fun updateRide(ride: RideEntity)

    @Query("UPDATE rides SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateAllRides()

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun deleteRide(id: String)
}
