package com.buzzbike.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.buzzbike.app.data.local.entity.ComponentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Persistence operations for the components installed on a bike.
 *
 * Every "active" query excludes retired components (`retiredAtMillis IS NULL`)
 * and orders by type then label, which is the stable order the garage UI and
 * the wear assessment consume.
 */
@Dao
interface ComponentDao {
    @Query(
        "SELECT * FROM components WHERE bikeId = :bikeId AND retiredAtMillis IS NULL " +
            "ORDER BY type ASC, label ASC, id ASC",
    )
    fun observeActiveComponents(bikeId: String): Flow<List<ComponentEntity>>

    @Query(
        "SELECT * FROM components WHERE bikeId = :bikeId AND retiredAtMillis IS NULL " +
            "ORDER BY type ASC, label ASC, id ASC",
    )
    suspend fun getActiveComponents(bikeId: String): List<ComponentEntity>

    @Query("SELECT * FROM components WHERE id = :id")
    suspend fun getComponent(id: String): ComponentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComponent(component: ComponentEntity)

    @Update
    suspend fun updateComponent(component: ComponentEntity)

    /** Soft-retires the component without touching its event history. */
    @Query("UPDATE components SET retiredAtMillis = :retiredAtMillis WHERE id = :id")
    suspend fun retireComponent(
        id: String,
        retiredAtMillis: Long,
    )

    @Query("DELETE FROM components WHERE id = :id")
    suspend fun deleteComponent(id: String)
}
