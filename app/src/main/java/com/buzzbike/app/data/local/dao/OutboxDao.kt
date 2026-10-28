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

    /**
     * Inserts an outbox row unless one with the same unique `rideId` already
     * exists.
     *
     * Returns the new row's id, or `-1` when the insert was ignored because the
     * ride was already enqueued. Callers use this for idempotent enqueues: an
     * ignored insert means the ride is already queued, not a failure.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(outbox: OutboxEntity): Long

    @Query(
        "SELECT * FROM outbox WHERE status = 'PENDING' AND attemptCount < maxAttempts " +
            "AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit",
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

    /**
     * Records a delivery failure and reschedules the row unless it exhausted
     * its attempts.
     *
     * The single UPDATE keeps the attempt counter and the derived status
     * consistent: SQLite evaluates every right-hand side against the old row,
     * so `attemptCount + 1` is the post-failure count in both places. Rows
     * that reach [OutboxEntity.maxAttempts] move to `FAILED` permanently;
     * the rest stay `PENDING` with [nextAttempt] as the backoff deadline.
     */
    @Query(
        "UPDATE outbox SET lastError = :error, attemptCount = attemptCount + 1, " +
            "updatedAt = :now, nextAttemptAt = :nextAttempt, " +
            "status = CASE WHEN attemptCount + 1 >= maxAttempts THEN 'FAILED' ELSE 'PENDING' END " +
            "WHERE id = :id",
    )
    suspend fun markFailed(
        id: String,
        error: String,
        now: Long,
        nextAttempt: Long,
    )

    @Query("DELETE FROM outbox WHERE status = 'SENT'")
    suspend fun deleteSent()

    /**
     * Counts every row still scheduled for delivery, including rows waiting
     * out a backoff window. Exhausted `FAILED` rows are deliberately excluded:
     * this is a "pending workload" gauge, not an "immediately eligible" one
     * (use [getPendingOutbox] for the latter).
     */
    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'")
    suspend fun countPending(): Int
}
