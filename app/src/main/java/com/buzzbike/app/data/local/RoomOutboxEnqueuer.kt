package com.buzzbike.app.data.local

import com.buzzbike.app.data.local.dao.OutboxDao
import com.buzzbike.app.data.local.entity.OutboxEntity
import com.buzzbike.app.domain.sync.OutboxEnqueuer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [OutboxEnqueuer].
 *
 * Inserts one immediately-eligible `PENDING` row. The insert is idempotent per
 * ride: if the ride is already in the outbox, the insert is ignored instead of
 * replacing it, so a duplicated `onRideFinished` cannot trigger a second
 * delivery. The only exception is an exhausted `FAILED` row: it is rewound to
 * `PENDING` so a retry requested later is not blocked forever by the unique
 * `rideId`. `PENDING`/`SENT` rows are left untouched. The payload stays opaque:
 * this adapter never parses or logs it.
 */
@Singleton
class RoomOutboxEnqueuer
    @Inject
    constructor(
        private val outboxDao: OutboxDao,
    ) : OutboxEnqueuer {
        override suspend fun enqueue(
            rideId: String,
            payload: ByteArray,
            maxAttempts: Int,
        ) {
            val now = System.currentTimeMillis()
            val attemptLimit = maxAttempts.coerceAtLeast(1)
            val insertedRowId =
                outboxDao.insertIfAbsent(
                    OutboxEntity(
                        id = UUID.randomUUID().toString(),
                        rideId = rideId,
                        payload = payload,
                        status = OutboxEntity.STATUS_PENDING,
                        // At least one attempt, even if the config says 0.
                        maxAttempts = attemptLimit,
                        createdAt = now,
                        updatedAt = now,
                        nextAttemptAt = now,
                    ),
                )
            if (insertedRowId != -1L) {
                return
            }
            outboxDao.resetFailedForRetry(rideId, now, attemptLimit)
        }
    }
