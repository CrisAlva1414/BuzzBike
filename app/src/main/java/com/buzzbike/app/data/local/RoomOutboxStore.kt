package com.buzzbike.app.data.local

import com.buzzbike.app.data.local.dao.OutboxDao
import com.buzzbike.app.data.local.entity.OutboxEntity
import com.buzzbike.app.domain.sync.OutboxItem
import com.buzzbike.app.domain.sync.OutboxStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [OutboxStore].
 *
 * This is the only place where the domain outbox port meets the persistence
 * schema. All retry decisions stay in `domain/sync/OutboxProcessor`; the
 * adapter just maps rows and delegates to [OutboxDao].
 */
@Singleton
class RoomOutboxStore
    @Inject
    constructor(
        private val outboxDao: OutboxDao,
    ) : OutboxStore {
        override suspend fun getPending(
            nowMillis: Long,
            limit: Int,
        ): List<OutboxItem> = outboxDao.getPendingOutbox(nowMillis, limit).map(OutboxEntity::toOutboxItem)

        override suspend fun markSent(
            id: String,
            nowMillis: Long,
        ) {
            outboxDao.markSent(id, nowMillis)
        }

        override suspend fun markFailed(
            id: String,
            error: String,
            nowMillis: Long,
            nextAttemptAtMillis: Long,
        ) {
            outboxDao.markFailed(id, error, nowMillis, nextAttemptAtMillis)
        }
    }

private fun OutboxEntity.toOutboxItem(): OutboxItem =
    OutboxItem(
        id = id,
        rideId = rideId,
        payload = payload,
        attemptCount = attemptCount,
        maxAttempts = maxAttempts,
    )
