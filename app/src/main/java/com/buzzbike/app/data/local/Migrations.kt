package com.buzzbike.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the sync outbox introduced in schema version 2.
 *
 * The SQL below mirrors exactly what Room generates for
 * [com.buzzbike.app.data.local.entity.OutboxEntity]: every non-null column is
 * declared `NOT NULL` (no DB-level defaults, since the entity always supplies
 * them from Kotlin) and the three indices match the entity's `@Index`
 * declaration.
 */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `outbox` (`id` TEXT NOT NULL, `rideId` TEXT NOT NULL, " +
                    "`payload` BLOB NOT NULL, `status` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, " +
                    "`maxAttempts` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_status` ON `outbox` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_createdAt` ON `outbox` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_rideId` ON `outbox` (`rideId`)")
        }
    }

/**
 * Makes `outbox.rideId` unique (schema version 3), keeping exactly one row per
 * ride: the one with the smallest `(createdAt, rowid)` pair.
 *
 * The `rowid` tie-breaker matters. Two enqueues can share a `createdAt`
 * timestamp, and keeping both would make the unique index creation fail. The
 * `rowid` is stable and monotonic for a rowid table, so the earliest inserted
 * row wins deterministically.
 *
 * A released app would also need to decide which duplicate payload is
 * authoritative; here the outbox is a retry queue, so the oldest copy is the
 * original enqueue and newer duplicates are superseded attempts.
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM `outbox` WHERE `rowid` NOT IN (" +
                    "SELECT `candidate`.`rowid` FROM `outbox` AS `candidate` WHERE NOT EXISTS (" +
                    "SELECT 1 FROM `outbox` AS `older` WHERE `older`.`rideId` = `candidate`.`rideId` " +
                    "AND (`older`.`createdAt` < `candidate`.`createdAt` OR " +
                    "(`older`.`createdAt` = `candidate`.`createdAt` AND `older`.`rowid` < `candidate`.`rowid`))))",
            )
            db.execSQL("DROP INDEX IF EXISTS `index_outbox_rideId`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_rideId` ON `outbox` (`rideId`)")
        }
    }

/**
 * Persists the calorie product band introduced in schema version 4.
 *
 * Before this version only the point estimate (`caloriesKcal`) was stored,
 * which made it impossible for the UI and the sync payload to show the model
 * and the uncertainty range (ADR-001, AGENTS.md §6.4). The new columns are
 * nullable: rides recorded by older versions keep `NULL` and are treated as
 * "no reliable calorie data" instead of inventing a range.
 */
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `rides` ADD COLUMN `caloriesLowKcal` REAL")
            db.execSQL("ALTER TABLE `rides` ADD COLUMN `caloriesHighKcal` REAL")
            db.execSQL("ALTER TABLE `rides` ADD COLUMN `calorieModel` TEXT")
        }
    }
