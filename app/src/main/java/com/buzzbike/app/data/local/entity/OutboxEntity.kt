package com.buzzbike.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A ride payload waiting to be delivered to Hermes.
 *
 * This table is the durable side of the sync retry loop: when the health
 * check fails or the device is offline, the ride is stored here and retried
 * later. [nextAttemptAt] is the backoff deadline and [attemptCount] tracks
 * how many delivery attempts have already happened.
 *
 * The [payload] is an opaque serialized blob (JSON) — `data/` never
 * interprets it, it only persists and hands it to `sync/`.
 */
@Entity(
    tableName = "outbox",
    indices = [Index("status"), Index("createdAt"), Index("rideId")],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    val rideId: String,
    val payload: ByteArray,
    val status: String,
    val attemptCount: Int = 0,
    val maxAttempts: Int = 5,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val nextAttemptAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
    }
}
