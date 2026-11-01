package com.buzzbike.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof that [MIGRATION_4_5] upgrades a real v4 database to the
 * current schema without losing existing rides.
 *
 * `MigrationTestHelper` recreates v4 from the exported schema JSON
 * (`app/schemas/.../4.json`) and validates the migrated database against the
 * v5 schema, so a missing column, table or index fails the test.
 */
@RunWith(AndroidJUnit4::class)
class Migration4To5InstrumentedTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Test
    fun given_v4Database_when_migratedToV5_thenProfileTableAndSnapshotColumnsExist() {
        helper.createDatabase(TEST_DATABASE, 4).apply {
            execSQL(
                "INSERT INTO rides (id, startTimeMillis, distanceM, movingTimeSeconds, " +
                    "stoppedTimeSeconds, elevationGainM, elevationLossM, caloriesKcal, " +
                    "caloriesLowKcal, caloriesHighKcal, calorieModel, avgSpeedMps, maxSpeedMps, " +
                    "isActive) VALUES ('ride-v4', 1000, 42.5, 3600, 120, 10.0, 8.0, 500.0, " +
                    "350.0, 650.0, 'COMPENDIUM_VELOCITY', 3.4, 9.9, 0)",
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(TEST_DATABASE, 5, true, MIGRATION_4_5)
        try {
            val profileColumns = columnNames(database, "user_profile")
            assertEquals(
                listOf("id", "updated_at_millis", "weight_kg", "height_cm", "age_years", "is_male"),
                profileColumns,
            )

            val rideColumns = columnNames(database, "rides")
            assertTrue("profile_weight_kg missing", "profile_weight_kg" in rideColumns)
            assertTrue("profile_height_cm missing", "profile_height_cm" in rideColumns)
            assertTrue("profile_age_years missing", "profile_age_years" in rideColumns)
            assertTrue("profile_is_male missing", "profile_is_male" in rideColumns)

            database
                .query(
                    "SELECT id, distanceM, caloriesKcal, profile_weight_kg, profile_height_cm, " +
                        "profile_age_years, profile_is_male FROM rides WHERE id = 'ride-v4'",
                ).use { cursor ->
                    assertTrue("existing ride must survive the migration", cursor.moveToFirst())
                    assertEquals("ride-v4", cursor.getString(0))
                    assertEquals(42.5, cursor.getDouble(1), 0.0)
                    assertEquals(500.0, cursor.getDouble(2), 0.0)
                    for (index in 3..6) {
                        assertTrue("snapshot column $index must be NULL for v4 rides", cursor.isNull(index))
                    }
                }
        } finally {
            database.close()
        }
    }

    private fun columnNames(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): List<String> =
        database
            .query("PRAGMA table_info(`$table`)", emptyArray<Any?>())
            .use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(nameIndex))
                    }
                }
            }

    private companion object {
        const val TEST_DATABASE = "migration-4-5-instrumented.db"
    }
}
