package com.buzzbike.app.domain.sync

/**
 * A ride payload that is waiting in the sync outbox.
 *
 * This is the domain view of a persisted `OutboxEntity`: only the data the
 * processor needs to decide whether to send, retry, or give up. The payload
 * stays opaque — `domain/` never interprets it.
 */
data class OutboxItem(
    val id: String,
    val rideId: String,
    val payload: ByteArray,
    val attemptCount: Int,
    val maxAttempts: Int,
)

/**
 * Port for the durable sync outbox.
 *
 * The domain owns the retry policy; persistence (Room) lives in `data/` as an
 * edge adapter (AGENTS.md §3). Implementations must not delete rows on
 * failure: a failed delivery stays in the outbox, rescheduled by
 * [markFailed], until it succeeds or exhausts [OutboxItem.maxAttempts].
 */
interface OutboxStore {
    /**
     * Returns up to [limit] rows whose backoff deadline is at or before
     * [nowMillis], in delivery order.
     */
    suspend fun getPending(
        nowMillis: Long,
        limit: Int,
    ): List<OutboxItem>

    /** Marks [id] as delivered at [nowMillis]. */
    suspend fun markSent(
        id: String,
        nowMillis: Long,
    )

    /**
     * Records a delivery failure for [id] and schedules the next attempt at
     * [nextAttemptAtMillis].
     */
    suspend fun markFailed(
        id: String,
        error: String,
        nowMillis: Long,
        nextAttemptAtMillis: Long,
    )

    /**
     * Deletes every row that reached `SENT`.
     *
     * Called after a drain so delivered payloads (including waypoints) do not
     * stay on disk forever.
     */
    suspend fun deleteSent()

    /**
     * Deletes exhausted `FAILED` rows whose last update is strictly older than
     * [cutoffMillis]. `PENDING` rows are never touched.
     */
    suspend fun deleteFailedOlderThan(cutoffMillis: Long)
}
