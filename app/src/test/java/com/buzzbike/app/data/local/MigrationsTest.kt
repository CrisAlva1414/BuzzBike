package com.buzzbike.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.data.local.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [MIGRATION_1_2], [MIGRATION_2_3] and [MIGRATION_3_4] against a real
 * file-backed database.
 *
 * `room-testing` is not a project dependency, so the source databases are
 * created with the exact SQL Room generated for their entities (rides +
 * track_points for v1, outbox for v2) and then opened through [AppDatabase] to
 * force the migrations. Room validates the resulting schema during `onUpgrade`,
 * so a wrong migration SQL fails these tests with a
 * `Migration didn't properly handle` error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        context.deleteDatabase(DATABASE_V2_NAME)
        context.deleteDatabase(DATABASE_V3_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
        context.deleteDatabase(DATABASE_V2_NAME)
        context.deleteDatabase(DATABASE_V3_NAME)
    }

    @Test
    fun given_v1Database_when_migratedToV2_then_outboxExistsAndAcceptsInsert() =
        runTest {
            createVersion1Database()

            val database =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

    @Test
    fun given_v2OutboxWithDuplicates_when_migratedToV3_then_keepsOldestAndEnforcesUniqueRideId() =
        runTest {
            createVersion2DatabaseWithDuplicateOutboxRows()

            val database =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, DATABASE_V2_NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .allowMainThreadQueries()
                    .build()

            try {
                database.openHelper.writableDatabase

                val remainingIds = readStrings(database, "SELECT id FROM outbox")
                assertEquals(3, remainingIds.size)
                assertEquals(
                    setOf("dup-old", "tie-first", "other"),
                    remainingIds.toSet(),
                )
                assertTrue(
                    "rideId index must be unique after migration",
                    isUniqueIndex(database, "index_outbox_rideId"),
                )

                // The unique index must reject a second row for the same ride.
                assertThrows(SQLiteConstraintException::class.java) {
                    database.openHelper.writableDatabase.execSQL(
                        "INSERT INTO outbox (id, rideId, payload, status, attemptCount, maxAttempts, " +
                            "lastError, createdAt, updatedAt, nextAttemptAt) VALUES " +
                            "('dup-newer', 'ride-dup', X'00', 'PENDING', 0, 5, NULL, 4000, 4000, 4000)",
                    )
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun given_v3Database_when_migratedToV4_thenCalorieColumnsAreAddedAndRowsSurvive() =
        runTest {
            createVersion3Database()

            val database =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, DATABASE_V3_NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .allowMainThreadQueries()
                    .build()

            try {
                database.openHelper.writableDatabase

                val columns = readColumn(database, "PRAGMA table_info(`rides`)", COLUMN_NAME_INDEX)
                assertTrue("caloriesLowKcal column missing after migration", "caloriesLowKcal" in columns)
                assertTrue("caloriesHighKcal column missing after migration", "caloriesHighKcal" in columns)
                assertTrue("calorieModel column missing after migration", "calorieModel" in columns)
                assertEquals(
                    "existing rides must survive the migration",
                    listOf("ride-legacy"),
                    readStrings(database, "SELECT id FROM rides"),
                )
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

    private fun createVersion2DatabaseWithDuplicateOutboxRows() {
        val path = context.getDatabasePath(DATABASE_V2_NAME)
        path.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            database.execSQL(V1_RIDES)
            database.execSQL(V1_TRACK_POINTS)
            database.execSQL(V1_TRACK_POINTS_RIDE_ID_INDEX)
            database.execSQL(V1_TRACK_POINTS_TIMESTAMP_INDEX)
            database.execSQL(V2_OUTBOX)
            database.execSQL(V2_OUTBOX_STATUS_INDEX)
            database.execSQL(V2_OUTBOX_CREATED_AT_INDEX)
            database.execSQL(V2_OUTBOX_RIDE_ID_INDEX)

            // Two rows with the same rideId, ordered by createdAt...
            database.execSQL(outboxInsert(id = "dup-old", rideId = "ride-dup", createdAt = 1_000L))
            database.execSQL(outboxInsert(id = "dup-new", rideId = "ride-dup", createdAt = 2_000L))
            // ...and two with identical createdAt: the earlier rowid must win.
            database.execSQL(outboxInsert(id = "tie-first", rideId = "ride-tie", createdAt = 3_000L))
            database.execSQL(outboxInsert(id = "tie-second", rideId = "ride-tie", createdAt = 3_000L))
            // A ride with a single row must survive untouched.
            database.execSQL(outboxInsert(id = "other", rideId = "ride-other", createdAt = 4_000L))

            database.version = 2
        } finally {
            database.close()
        }
    }

    private fun createVersion3Database() {
        val path = context.getDatabasePath(DATABASE_V3_NAME)
        path.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            database.execSQL(V1_RIDES)
            database.execSQL(V1_TRACK_POINTS)
            database.execSQL(V1_TRACK_POINTS_RIDE_ID_INDEX)
            database.execSQL(V1_TRACK_POINTS_TIMESTAMP_INDEX)
            database.execSQL(V2_OUTBOX)
            database.execSQL(V2_OUTBOX_STATUS_INDEX)
            database.execSQL(V2_OUTBOX_CREATED_AT_INDEX)
            database.execSQL(V3_OUTBOX_RIDE_ID_INDEX)
            database.execSQL(
                "INSERT INTO rides (id, startTimeMillis, distanceM, movingTimeSeconds, " +
                    "stoppedTimeSeconds, elevationGainM, elevationLossM, avgSpeedMps, " +
                    "maxSpeedMps, isActive) VALUES ('ride-legacy', 1000, 10.0, 60, 0, 1.0, 0.5, 2.0, 3.0, 0)",
            )

            database.version = 3
        } finally {
            database.close()
        }
    }

    private fun outboxInsert(
        id: String,
        rideId: String,
        createdAt: Long,
    ): String =
        "INSERT INTO outbox (id, rideId, payload, status, attemptCount, maxAttempts, lastError, " +
            "createdAt, updatedAt, nextAttemptAt) VALUES " +
            "('$id', '$rideId', X'01', 'PENDING', 0, 5, NULL, $createdAt, $createdAt, $createdAt)"

    private fun isUniqueIndex(
        database: AppDatabase,
        name: String,
    ): Boolean =
        database
            .query("PRAGMA index_list(`outbox`)", null)
            .use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == name) {
                        found = cursor.getInt(uniqueColumn) == 1
                    }
                }
                found
            }

    private fun readStrings(
        database: AppDatabase,
        sql: String,
    ): List<String> = readColumn(database, sql, 0)

    private fun readColumn(
        database: AppDatabase,
        sql: String,
        columnIndex: Int,
    ): List<String> =
        database
            .query(sql, null)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(columnIndex))
                    }
                }
            }

    private companion object {
        const val DATABASE_NAME = "migration-1-2.db"
        const val DATABASE_V2_NAME = "migration-2-3.db"
        const val DATABASE_V3_NAME = "migration-3-4.db"
        const val COLUMN_NAME_INDEX = 1
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
        const val V2_OUTBOX =
            "CREATE TABLE IF NOT EXISTS `outbox` (`id` TEXT NOT NULL, `rideId` TEXT NOT NULL, " +
                "`payload` BLOB NOT NULL, `status` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, " +
                "`maxAttempts` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        const val V2_OUTBOX_STATUS_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_outbox_status` ON `outbox` (`status`)"
        const val V2_OUTBOX_CREATED_AT_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_outbox_createdAt` ON `outbox` (`createdAt`)"
        const val V2_OUTBOX_RIDE_ID_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_outbox_rideId` ON `outbox` (`rideId`)"
        const val V3_OUTBOX_RIDE_ID_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_rideId` ON `outbox` (`rideId`)"
    }
}
