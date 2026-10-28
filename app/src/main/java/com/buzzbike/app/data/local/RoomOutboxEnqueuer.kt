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
 * Inserts one immediately-eligible `PENDING` row. The payload stays opaque:
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
        ) {
            val now = System.currentTimeMillis()
            outboxDao.insert(
                OutboxEntity(
                    id = UUID.randomUUID().toString(),
                    rideId = rideId,
                    payload = payload,
                    status = OutboxEntity.STATUS_PENDING,
                    createdAt = now,
                    updatedAt = now,
                    nextAttemptAt = now,
                ),
            )
        }
    }
