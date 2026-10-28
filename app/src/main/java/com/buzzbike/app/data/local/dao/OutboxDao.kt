package com.buzzbike.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.buzzbike.app.data.local.entity.OutboxEntity

/**
 * Persistence operations for the sync outbox.
 *
 * The status column is intentionally a plain string so the sync layer can
 * evolve the retry state machine without a schema migration.
 */
@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(outbox: OutboxEntity)

    @Query(
        "SELECT * FROM outbox WHERE status = 'PENDING' AND nextAttemptAt <= :now " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun getPendingOutbox(
        now: Long,
        limit: Int,
    ): List<OutboxEntity>

    @Query("UPDATE outbox SET status = 'SENT', updatedAt = :now WHERE id = :id")
    suspend fun markSent(
        id: String,
        now: Long,
    )

    @Query(
        "UPDATE outbox SET status = 'FAILED', lastError = :error, " +
            "attemptCount = attemptCount + 1, updatedAt = :now, nextAttemptAt = :nextAttempt WHERE id = :id",
    )
    suspend fun markFailed(
        id: String,
        error: String,
        now: Long,
        nextAttempt: Long,
    )

    @Query("DELETE FROM outbox WHERE status = 'SENT'")
    suspend fun deleteSent()

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'")
    suspend fun countPending(): Int
}
