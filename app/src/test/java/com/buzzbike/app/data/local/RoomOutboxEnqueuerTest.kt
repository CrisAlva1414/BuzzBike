package com.buzzbike.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.data.local.dao.OutboxDao
import com.buzzbike.app.data.local.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests for [RoomOutboxEnqueuer] against a Room in-memory database.
 *
 * Robolectric provides an Android `Context` so the adapter can be exercised
 * without an emulator or a physical device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomOutboxEnqueuerTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: OutboxDao
    private lateinit var enqueuer: RoomOutboxEnqueuer

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
        enqueuer = RoomOutboxEnqueuer(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun given_maxAttempts_when_enqueue_then_rowIsPendingWithThatLimit() =
        runTest {
            enqueuer.enqueue(RIDE_ID, PAYLOAD, maxAttempts = 7)

            assertEquals(1L, countAllRows())
            assertEquals(7L, readLong(RIDE_ID, "maxAttempts"))
            assertEquals(OutboxEntity.STATUS_PENDING, readColumn(RIDE_ID, "status"))
        }

    @Test
    fun given_zeroMaxAttempts_when_enqueue_then_coercedToOne() =
        runTest {
            enqueuer.enqueue(RIDE_ID, PAYLOAD, maxAttempts = 0)

            assertEquals(1L, countAllRows())
            assertEquals(1L, readLong(RIDE_ID, "maxAttempts"))
        }

    @Test
    fun given_sameRideId_when_enqueueTwice_then_onlyOneRow() =
        runTest {
            enqueuer.enqueue(RIDE_ID, PAYLOAD, maxAttempts = 7)
            enqueuer.enqueue(RIDE_ID, OTHER_PAYLOAD, maxAttempts = 3)

            assertEquals(1L, countAllRows())
            // insertIfAbsent never replaces the first row for a ride.
            assertEquals(7L, readLong(RIDE_ID, "maxAttempts"))
        }

    private fun countAllRows(): Long =
        database
            .query("SELECT COUNT(*) FROM outbox", null)
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }

    private fun readColumn(
        rideId: String,
        column: String,
    ): String? =
        database
            .query("SELECT $column FROM outbox WHERE rideId = ?", arrayOf(rideId))
            .use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private fun readLong(
        rideId: String,
        column: String,
    ): Long =
        database
            .query("SELECT $column FROM outbox WHERE rideId = ?", arrayOf(rideId))
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }

    private companion object {
        const val RIDE_ID = "ride-1"
        val PAYLOAD = byteArrayOf(1, 2, 3)
        val OTHER_PAYLOAD = byteArrayOf(4, 5, 6)
    }
}
