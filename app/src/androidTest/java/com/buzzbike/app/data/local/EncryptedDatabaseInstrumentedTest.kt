package com.buzzbike.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.sync.AndroidKeystoreSecretSealer
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-only proof that the Room database is actually encrypted by SQLCipher.
 *
 * Robolectric's Room is in-memory and never loads the native library, so the
 * JVM suite cannot catch a plaintext database. This test opens a real file,
 * round-trips a ride through the DAO and then inspects the first bytes on
 * disk: a plaintext SQLite file always starts with `SQLite format 3\0`,
 * SQLCipher does not.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun given_encryptedDatabase_when_rideRoundTrips_thenFileIsNotPlainSqlite() =
        runBlocking {
            val sealer = AndroidKeystoreSecretSealer()
            val passphrase = DatabasePassphraseProvider(context, sealer).getPassphrase()
            val encryptedDatabase =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .allowMainThreadQueries()
                    .build()
            database = encryptedDatabase

            encryptedDatabase.rideDao().insertRide(RideEntity(id = RIDE_ID, startTimeMillis = START_TIME_MILLIS))
            val stored = requireNotNull(encryptedDatabase.rideDao().getRideById(RIDE_ID))
            assertEquals(RIDE_ID, stored.id)

            encryptedDatabase.close()
            database = null

            val header = readHeader(context.getDatabasePath(DATABASE_NAME))
            assertFalse(
                "encrypted database must not expose the plaintext SQLite header",
                header.startsWith(SQLITE_PLAINTEXT_HEADER),
            )
        }

    private fun readHeader(file: File): String {
        val bytes = ByteArray(HEADER_BYTES)
        file.inputStream().use { input ->
            var read = 0
            while (read < bytes.size) {
                val count = input.read(bytes, read, bytes.size - read)
                if (count < 0) break
                read += count
            }
        }
        return String(bytes, Charsets.US_ASCII)
    }

    companion object {
        const val DATABASE_NAME = "instrumented-encrypted.db"
        const val RIDE_ID = "ride-instrumented-1"
        const val START_TIME_MILLIS = 1_700_000_000_000L
        const val HEADER_BYTES = 16
        const val SQLITE_PLAINTEXT_HEADER = "SQLite format 3"

        /**
         * `BuzzBikeApplication.onCreate` loads the native library, but a
         * custom instrumentation Application would skip it. Loading here is
         * idempotent and keeps this test independent of the app class.
         */
        @BeforeClass
        @JvmStatic
        fun loadSqlCipher() {
            System.loadLibrary("sqlcipher")
        }
    }
}
