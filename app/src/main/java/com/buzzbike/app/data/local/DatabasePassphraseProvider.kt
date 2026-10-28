package com.buzzbike.app.data.local

import android.content.Context
import android.util.Base64
import com.buzzbike.app.domain.sync.SecretSealer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the SQLCipher passphrase for the encrypted Room database.
 *
 * The passphrase is generated once with [SecureRandom] and sealed with the
 * Android Keystore-backed [SecretSealer] (ADR-003 envelope encryption). Only
 * the sealed blob is persisted in `SharedPreferences`; **the passphrase is
 * never stored in clear**, here or anywhere else on disk.
 *
 * A corrupt blob fails closed: the database cannot be opened without the
 * original passphrase, so regenerating one would silently make every existing
 * ride unreadable.
 */
@Singleton
class DatabasePassphraseProvider
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val sealer: SecretSealer,
    ) {
        private val preferences =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        /**
         * Returns the database passphrase, creating and sealing a fresh one on
         * first use.
         *
         * The returned array is the only clear-text copy. It is deliberately
         * **not** zeroed here: SQLCipher's helper factory keeps it for the
         * lifetime of the database and reuses it when the database is reopened.
         */
        @Synchronized
        fun getPassphrase(): ByteArray {
            val stored = preferences.getString(KEY_SEALED_PASSPHRASE, null)
            return if (stored == null) generateAndPersist() else unsealOrFail(stored)
        }

        private fun generateAndPersist(): ByteArray {
            val passphrase = ByteArray(PASSPHRASE_BYTES)
            SECURE_RANDOM.nextBytes(passphrase)
            val encoded = Base64.encodeToString(sealer.seal(passphrase), Base64.NO_WRAP)
            check(preferences.edit().putString(KEY_SEALED_PASSPHRASE, encoded).commit()) {
                "failed to persist the sealed database passphrase"
            }
            return passphrase
        }

        private fun unsealOrFail(encoded: String): ByteArray {
            val sealed =
                try {
                    Base64.decode(encoded, Base64.NO_WRAP)
                } catch (expected: IllegalArgumentException) {
                    throw IllegalStateException(CORRUPT_MESSAGE)
                }
            return try {
                sealer.unseal(sealed)
            } catch (expected: IllegalArgumentException) {
                throw IllegalStateException(CORRUPT_MESSAGE)
            }
        }

        private companion object {
            const val PREFERENCES_NAME = "db_secrets_v1"
            const val KEY_SEALED_PASSPHRASE = "passphrase_sealed"

            /** 256 bits of entropy for the SQLCipher key. */
            const val PASSPHRASE_BYTES = 32
            const val CORRUPT_MESSAGE = "database passphrase is corrupt"
            val SECURE_RANDOM = SecureRandom()
        }
    }
