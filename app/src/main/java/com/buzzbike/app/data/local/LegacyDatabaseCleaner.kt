package com.buzzbike.app.data.local

import android.content.Context

/**
 * Removes the plaintext Room database left behind by pre-encryption builds.
 *
 * BuzzBike is pre-release (`versionCode = 1`) and has no real users yet, so
 * dropping the legacy plaintext database is accepted: keeping it around would
 * leave GPS coordinates unencrypted on disk, violating the local-first/privacy
 * contract (AGENTS.md §7).
 *
 * A released app with real users would need an export/import migration that
 * opens the plaintext database, copies every row into the encrypted one, and
 * only then deletes the plaintext files.
 */
object LegacyDatabaseCleaner {
    /**
     * Deletes the plaintext database (and its `-wal`/`-shm` sidecars) whenever
     * it exists, regardless of whether the encrypted database exists.
     *
     * The plaintext database must never survive: if a crash leaves both files
     * behind, the unencrypted GPS coordinates would stay on disk forever. The
     * existence check keeps the cleanup idempotent — with no plaintext file the
     * call is a no-op. `deleteDatabase` removes the sidecars as well.
     */
    fun deletePlaintextDatabaseIfNeeded(context: Context) {
        val plaintextExists = context.getDatabasePath(LEGACY_DATABASE_NAME).exists()
        if (plaintextExists) {
            check(context.deleteDatabase(LEGACY_DATABASE_NAME)) {
                "failed to delete the plaintext database"
            }
        }
    }

    private const val LEGACY_DATABASE_NAME = "buzzbike.db"
}
