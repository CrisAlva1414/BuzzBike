package com.buzzbike.app.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.data.local.AppDatabase
import com.buzzbike.app.data.local.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests for [OutboxDao] against a Room in-memory database.
 *
 * Robolectric provides an Android `Context` so the DAO can be exercised
 * without an emulator or a physical device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OutboxDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: OutboxDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        dao = database.outboxDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun given_pendingRow_when_inserted_then_getPendingReturnsIt() =
        runTest {
            dao.insert(outbox(id = "ob-1", createdAt = NOW, nextAttemptAt = NOW))

            val pending = dao.getPendingOutbox(now = NOW, limit = 10)

            assertEquals(1, pending.size)
            val row = pending.single()
            assertEquals("ob-1", row.id)
            assertEquals("ride-ob-1", row.rideId)
            assertArrayEquals(PAYLOAD, row.payload)
            assertEquals(OutboxEntity.STATUS_PENDING, row.status)
            assertEquals(0, row.attemptCount)
            assertEquals(DEFAULT_MAX_ATTEMPTS, row.maxAttempts)
        }

    @Test
    fun given_rowDueLater_when_getPending_then_excludedUntilNextAttempt() =
        runTest {
            dao.insert(outbox(id = "ob-1", createdAt = NOW, nextAttemptAt = NOW + BACKOFF_MS))

            val tooEarly = dao.getPendingOutbox(now = NOW + BACKOFF_MS - 1, limit = 10)
            val dueNow = dao.getPendingOutbox(now = NOW + BACKOFF_MS, limit = 10)

            assertTrue(tooEarly.isEmpty())
            assertEquals(listOf("ob-1"), dueNow.map { it.id })
        }

    @Test
    fun given_nonPendingStatus_when_getPending_then_excluded() =
        runTest {
            dao.insert(outbox(id = "pending", createdAt = NOW, nextAttemptAt = NOW))
            dao.insert(
                outbox(
                    id = "sent",
                    status = OutboxEntity.STATUS_SENT,
                    createdAt = NOW,
                    nextAttemptAt = NOW,
                ),
            )
            dao.insert(
                outbox(
                    id = "failed",
                    status = OutboxEntity.STATUS_FAILED,
                    createdAt = NOW,
                    nextAttemptAt = NOW,
                ),
            )

            val pending = dao.getPendingOutbox(now = NOW, limit = 10)

            assertEquals(listOf("pending"), pending.map { it.id })
        }

    @Test
    fun given_severalPendingRows_when_getPending_then_orderedByCreatedAtWithLimit() =
        runTest {
            dao.insert(outbox(id = "third", createdAt = 300L, nextAttemptAt = NOW))
            dao.insert(outbox(id = "first", createdAt = 100L, nextAttemptAt = NOW))
            dao.insert(outbox(id = "second", createdAt = 200L, nextAttemptAt = NOW))

            val pending = dao.getPendingOutbox(now = NOW, limit = 2)

            assertEquals(listOf("first", "second"), pending.map { it.id })
        }

    @Test
    fun given_pendingRow_when_markSent_then_noLongerPending() =
        runTest {
            dao.insert(outbox(id = "ob-1", createdAt = NOW, nextAttemptAt = NOW))

            dao.markSent(id = "ob-1", now = NOW + UPDATED_MS)

            assertEquals(0, dao.countPending())
            assertTrue(dao.getPendingOutbox(now = NOW + UPDATED_MS, limit = 10).isEmpty())
            assertEquals(OutboxEntity.STATUS_SENT, readColumn("ob-1", "status"))
            assertEquals(NOW + UPDATED_MS, readLong("ob-1", "updatedAt"))
        }

    @Test
    fun given_pendingRow_when_markFailed_then_recordsAttemptAndBackoff() =
        runTest {
            dao.insert(outbox(id = "ob-1", createdAt = NOW, nextAttemptAt = NOW))

            dao.markFailed(
                id = "ob-1",
                error = "HTTP 503",
                now = NOW + UPDATED_MS,
                nextAttempt = NOW + BACKOFF_MS,
            )

            // attemptCount 1 < maxAttempts (5): the row stays PENDING and is
            // retried once the backoff deadline passes. The failure metadata
            // is persisted either way.
            assertEquals(1, dao.countPending())
            assertTrue(dao.getPendingOutbox(now = NOW + BACKOFF_MS - 1, limit = 10).isEmpty())
            assertEquals(listOf("ob-1"), dao.getPendingOutbox(now = NOW + BACKOFF_MS, limit = 10).map { it.id })
            assertEquals(OutboxEntity.STATUS_PENDING, readColumn("ob-1", "status"))
            assertEquals(1L, readLong("ob-1", "attemptCount"))
            assertEquals("HTTP 503", readColumn("ob-1", "lastError"))
            assertEquals(NOW + UPDATED_MS, readLong("ob-1", "updatedAt"))
            assertEquals(NOW + BACKOFF_MS, readLong("ob-1", "nextAttemptAt"))
        }

    @Test
    fun given_failedAttemptBelowMax_when_markFailed_thenStillPendingAndEligible() =
        runTest {
            dao.insert(
                outboxWithAttempts(
                    id = "ob-1",
                    attemptCount = 0,
                    maxAttempts = 3,
                    createdAt = NOW,
                    nextAttemptAt = NOW,
                ),
            )

            dao.markFailed(
                id = "ob-1",
                error = "offline",
                now = NOW + UPDATED_MS,
                nextAttempt = NOW,
            )

            assertEquals(OutboxEntity.STATUS_PENDING, readColumn("ob-1", "status"))
            assertEquals(1L, readLong("ob-1", "attemptCount"))
            assertEquals(listOf("ob-1"), dao.getPendingOutbox(now = NOW + UPDATED_MS, limit = 10).map { it.id })
        }

    @Test
    fun given_failedAttemptReachesMax_when_markFailed_thenNotEligible() =
        runTest {
            dao.insert(
                outboxWithAttempts(
                    id = "ob-1",
                    attemptCount = 2,
                    maxAttempts = 3,
                    createdAt = NOW,
                    nextAttemptAt = NOW,
                ),
            )

            dao.markFailed(
                id = "ob-1",
                error = "HTTP 503",
                now = NOW + UPDATED_MS,
                nextAttempt = NOW,
            )

            assertEquals(OutboxEntity.STATUS_FAILED, readColumn("ob-1", "status"))
            assertEquals(3L, readLong("ob-1", "attemptCount"))
            assertTrue(dao.getPendingOutbox(now = NOW + BACKOFF_MS, limit = 10).isEmpty())
        }

    @Test
    fun given_nextAttemptInFuture_when_getPending_thenNotReturned() =
        runTest {
            dao.insert(outbox(id = "ob-1", createdAt = NOW, nextAttemptAt = NOW + BACKOFF_MS))

            assertTrue(dao.getPendingOutbox(now = NOW, limit = 10).isEmpty())
            assertEquals(listOf("ob-1"), dao.getPendingOutbox(now = NOW + BACKOFF_MS, limit = 10).map { it.id })
        }

    @Test
    fun given_sentRows_when_deleteSent_then_onlySentAreDeleted() =
        runTest {
            dao.insert(outbox(id = "pending", createdAt = NOW, nextAttemptAt = NOW))
            dao.insert(
                outbox(
                    id = "sent",
                    status = OutboxEntity.STATUS_SENT,
                    createdAt = NOW,
                    nextAttemptAt = NOW,
                ),
            )

            dao.deleteSent()

            assertEquals(1, countAllRows())
            assertEquals(1, dao.countPending())
        }

    @Test
    fun given_mixedRows_when_countPending_then_countsOnlyPending() =
        runTest {
            dao.insert(outbox(id = "pending-1", createdAt = NOW, nextAttemptAt = NOW))
            dao.insert(outbox(id = "pending-2", createdAt = NOW, nextAttemptAt = NOW))
            dao.insert(
                outbox(
                    id = "sent",
                    status = OutboxEntity.STATUS_SENT,
                    createdAt = NOW,
                    nextAttemptAt = NOW,
                ),
            )
            dao.insert(
                outbox(
                    id = "failed",
                    status = OutboxEntity.STATUS_FAILED,
                    createdAt = NOW,
                    nextAttemptAt = NOW,
                ),
            )

            assertEquals(2, dao.countPending())
        }

    @Test
    fun given_existingId_when_insert_then_rowIsReplaced() =
        runTest {
            dao.insert(outbox(id = "ob-1", createdAt = NOW, nextAttemptAt = NOW))
            dao.insert(
                outbox(
                    id = "ob-1",
                    status = OutboxEntity.STATUS_SENT,
                    createdAt = NOW,
                    nextAttemptAt = NOW,
                ),
            )

            assertEquals(1, countAllRows())
            assertEquals(OutboxEntity.STATUS_SENT, readColumn("ob-1", "status"))
        }

    private fun outbox(
        id: String,
        status: String = OutboxEntity.STATUS_PENDING,
        createdAt: Long,
        nextAttemptAt: Long,
    ) = OutboxEntity(
        id = id,
        rideId = "ride-$id",
        payload = PAYLOAD.copyOf(),
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
        nextAttemptAt = nextAttemptAt,
    )

    private fun outboxWithAttempts(
        id: String,
        attemptCount: Int,
        maxAttempts: Int,
        createdAt: Long,
        nextAttemptAt: Long,
    ) = outbox(id = id, createdAt = createdAt, nextAttemptAt = nextAttemptAt)
        .copy(attemptCount = attemptCount, maxAttempts = maxAttempts)

    private fun countAllRows(): Long =
        database
            .query("SELECT COUNT(*) FROM outbox", null)
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }

    private fun readColumn(
        id: String,
        column: String,
    ): String? =
        database
            .query("SELECT $column FROM outbox WHERE id = ?", arrayOf(id))
            .use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private fun readLong(
        id: String,
        column: String,
    ): Long =
        database
            .query("SELECT $column FROM outbox WHERE id = ?", arrayOf(id))
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }

    private companion object {
        const val NOW = 1_000L
        const val UPDATED_MS = 500L
        const val BACKOFF_MS = 60_000L
        const val DEFAULT_MAX_ATTEMPTS = 5
        val PAYLOAD = byteArrayOf(1, 2, 3)
    }
}
