package com.buzzbike.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.buzzbike.app.data.local.entity.BikeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Persistence operations for garage bikes.
 *
 * Deleting a bike removes only the bike row. Components and their events
 * disappear through the cascade foreign keys, while `rides.bike_id` is left
 * untouched on purpose: it is a soft historical reference (schema v6 has no
 * SQL foreign key from rides to bikes), so the ride detail can later show that
 * the bike no longer exists.
 */
@Dao
interface BikeDao {
    @Query("SELECT * FROM bikes ORDER BY isArchived ASC, createdAtMillis ASC, name ASC")
    fun observeBikes(): Flow<List<BikeEntity>>

    @Query("SELECT * FROM bikes WHERE id = :id")
    suspend fun getBike(id: String): BikeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBike(bike: BikeEntity)

    @Update
    suspend fun updateBike(bike: BikeEntity)

    @Query("DELETE FROM bikes WHERE id = :id")
    suspend fun deleteBike(id: String)

    /**
     * Deletes the bike and everything that depends on it.
     *
     * Only the bike row is removed here: components and their events fall
     * through the cascade foreign keys. Rides are never deleted nor
     * unassigned; their `bike_id` keeps pointing at the vanished bike as a
     * historical reference, and wear accumulation ignores it because the
     * usage queries join on `bike_id = :bikeId` of an existing bike.
     */
    @Transaction
    suspend fun deleteBikeCascade(bikeId: String) {
        deleteBike(bikeId)
    }
}
