package com.buzzbike.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof that [MIGRATION_5_6] upgrades a real v5 database to the
 * garage schema without losing existing rides.
 *
 * `MigrationTestHelper` recreates v5 from the exported schema JSON
 * (`app/schemas/.../5.json`) and validates the migrated database against v6, so
 * a missing table, column, index or foreign key fails the test.
 */
@RunWith(AndroidJUnit4::class)
class Migration5To6InstrumentedTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Test
    fun given_v5Database_when_migratedToV6_thenGarageTablesAndBikeAssignmentExist() {
        helper.createDatabase(TEST_DATABASE, 5).apply {
            execSQL(
                "INSERT INTO rides (id, startTimeMillis, distanceM, movingTimeSeconds, " +
                    "stoppedTimeSeconds, elevationGainM, elevationLossM, caloriesKcal, " +
                    "caloriesLowKcal, caloriesHighKcal, calorieModel, profile_weight_kg, " +
                    "profile_height_cm, profile_age_years, profile_is_male, avgSpeedMps, " +
                    "maxSpeedMps, isActive) VALUES ('ride-v5', 1000, 42.5, 3600, 120, 10.0, " +
                    "8.0, 500.0, 350.0, 650.0, 'COMPENDIUM_VELOCITY', 70.0, 175.0, 30, 1, " +
                    "3.4, 9.9, 0)",
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(TEST_DATABASE, 6, true, MIGRATION_5_6)
        try {
            val tables = readStrings(database, "SELECT name FROM sqlite_master WHERE type = 'table'")
            assertTrue("bikes table missing", "bikes" in tables)
            assertTrue("components table missing", "components" in tables)
            assertTrue("component_events table missing", "component_events" in tables)

            val rideColumns = columnNames(database, "rides")
            assertTrue("bike_id column missing", "bike_id" in rideColumns)
            assertTrue("rides.bike_id index missing", "index_rides_bike_id" in indexNames(database, "rides"))
            assertTrue(
                "components.bikeId index missing",
                "index_components_bikeId" in indexNames(database, "components"),
            )
            assertTrue(
                "component_events.componentId index missing",
                "index_component_events_componentId" in indexNames(database, "component_events"),
            )

            database
                .query(
                    "SELECT id, distanceM, caloriesKcal, profile_weight_kg, bike_id " +
                        "FROM rides WHERE id = 'ride-v5'",
                ).use { cursor ->
                    assertTrue("existing ride must survive the migration", cursor.moveToFirst())
                    assertEquals("ride-v5", cursor.getString(0))
                    assertEquals(42.5, cursor.getDouble(1), 0.0)
                    assertEquals(500.0, cursor.getDouble(2), 0.0)
                    assertEquals(70.0, cursor.getDouble(3), 0.0)
                    assertTrue("legacy rides must start unassigned", cursor.isNull(4))
                }

            // The cascade foreign keys are part of the migrated schema. The raw
            // helper connection does not enable them by default.
            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL(
                "INSERT INTO bikes (id, name, notes, createdAtMillis, isArchived) " +
                    "VALUES ('bike-1', 'Gravel', NULL, 1000, 0)",
            )
            database.execSQL(
                "INSERT INTO components (id, bikeId, type, label, installedAtMillis) " +
                    "VALUES ('component-1', 'bike-1', 'CHAIN', 'Chain', 1000)",
            )
            database.execSQL(
                "INSERT INTO component_events (id, componentId, type, atMillis) " +
                    "VALUES ('event-1', 'component-1', 'SERVICE', 2000)",
            )
            database.execSQL("DELETE FROM bikes WHERE id = 'bike-1'")
            assertEquals(0L, countRows(database, "components"))
            assertEquals(0L, countRows(database, "component_events"))
        } finally {
            database.close()
        }
    }

    private fun columnNames(
        database: SupportSQLiteDatabase,
        table: String,
    ): List<String> = readColumn(database, "PRAGMA table_info(`$table`)", COLUMN_NAME_INDEX)

    private fun indexNames(
        database: SupportSQLiteDatabase,
        table: String,
    ): List<String> = readColumn(database, "PRAGMA index_list(`$table`)", COLUMN_NAME_INDEX)

    private fun readStrings(
        database: SupportSQLiteDatabase,
        sql: String,
    ): List<String> = readColumn(database, sql, 0)

    private fun readColumn(
        database: SupportSQLiteDatabase,
        sql: String,
        columnIndex: Int,
    ): List<String> =
        database.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(columnIndex))
                }
            }
        }

    private fun countRows(
        database: SupportSQLiteDatabase,
        table: String,
    ): Long =
        database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private companion object {
        const val TEST_DATABASE = "migration-5-6-instrumented.db"
        const val COLUMN_NAME_INDEX = 1
    }
}
