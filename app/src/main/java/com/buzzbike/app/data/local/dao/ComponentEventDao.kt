package com.buzzbike.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.buzzbike.app.data.local.entity.ComponentEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Persistence operations for component wear events.
 *
 * The latest `SERVICE` event is the accumulation baseline for the component:
 * usage is summed from its timestamp, not from the install date.
 */
@Dao
interface ComponentEventDao {
    @Query("SELECT * FROM component_events WHERE componentId = :componentId ORDER BY atMillis DESC, id DESC")
    fun observeEvents(componentId: String): Flow<List<ComponentEventEntity>>

    @Query(
        "SELECT * FROM component_events WHERE componentId = :componentId AND type = 'SERVICE' " +
            "ORDER BY atMillis DESC, id DESC LIMIT 1",
    )
    suspend fun getLastServiceEvent(componentId: String): ComponentEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: ComponentEventEntity)
}
