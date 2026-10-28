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
