package com.buzzbike.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.data.local.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [MIGRATION_1_2] against a real file-backed database.
 *
 * `room-testing` is not a project dependency, so the v1 database is created
 * with the exact SQL Room generated for the v1 entities (rides + track_points)
 * and then opened through [AppDatabase] to force the 1 -> 2 migration. Room
 * validates the resulting schema during `onUpgrade`, so a wrong migration SQL
 * fails this test with a `Migration didn't properly handle` error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun given_v1Database_when_migratedToV2_then_outboxExistsAndAcceptsInsert() =
        runTest {
            createVersion1Database()

            val database =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                    .addMigrations(MIGRATION_1_2)
                    .allowMainThreadQueries()
                    .build()

            try {
                database.openHelper.writableDatabase

                val tables = readStrings(database, "SELECT name FROM sqlite_master WHERE type = 'table'")
                assertTrue("outbox table missing after migration", "outbox" in tables)

                val indices =
                    readStrings(
                        database,
                        "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'outbox'",
                    )
                assertTrue("status index missing after migration", "index_outbox_status" in indices)
                assertTrue("createdAt index missing after migration", "index_outbox_createdAt" in indices)
                assertTrue("rideId index missing after migration", "index_outbox_rideId" in indices)

                database.outboxDao().insert(
                    OutboxEntity(
                        id = "ob-1",
                        rideId = "ride-1",
                        payload = byteArrayOf(1, 2, 3),
                        status = OutboxEntity.STATUS_PENDING,
                        createdAt = NOW,
                        updatedAt = NOW,
                        nextAttemptAt = NOW,
                    ),
                )

                assertEquals(1, database.outboxDao().countPending())
            } finally {
                database.close()
            }
        }

    private fun createVersion1Database() {
        val path = context.getDatabasePath(DATABASE_NAME)
        path.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            database.execSQL(V1_RIDES)
            database.execSQL(V1_TRACK_POINTS)
            database.execSQL(V1_TRACK_POINTS_RIDE_ID_INDEX)
            database.execSQL(V1_TRACK_POINTS_TIMESTAMP_INDEX)
            database.version = 1
        } finally {
            database.close()
        }
    }

    private fun readStrings(
        database: AppDatabase,
        sql: String,
    ): List<String> =
        database
            .query(sql, null)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }

    private companion object {
        const val DATABASE_NAME = "migration-1-2.db"
        const val NOW = 1_000L
        const val V1_RIDES =
            "CREATE TABLE IF NOT EXISTS `rides` (`id` TEXT NOT NULL, `startTimeMillis` INTEGER NOT NULL, " +
                "`endTimeMillis` INTEGER, `distanceM` REAL NOT NULL, `movingTimeSeconds` INTEGER NOT NULL, " +
                "`stoppedTimeSeconds` INTEGER NOT NULL, `elevationGainM` REAL NOT NULL, " +
                "`elevationLossM` REAL NOT NULL, `caloriesKcal` REAL, `avgSpeedMps` REAL NOT NULL, " +
                "`maxSpeedMps` REAL NOT NULL, `isActive` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        const val V1_TRACK_POINTS =
            "CREATE TABLE IF NOT EXISTS `track_points` (`id` TEXT NOT NULL, `rideId` TEXT NOT NULL, " +
                "`timestampNanos` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
                "`altitudeMslM` REAL, `altitudeEllipsoidM` REAL, `accuracyH` REAL, `accuracyV` REAL, " +
                "`speedMps` REAL, `bearing` REAL, `pressureHpa` REAL, PRIMARY KEY(`id`))"
        const val V1_TRACK_POINTS_RIDE_ID_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_track_points_rideId` ON `track_points` (`rideId`)"
        const val V1_TRACK_POINTS_TIMESTAMP_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_track_points_timestampNanos` ON `track_points` (`timestampNanos`)"
    }
}
