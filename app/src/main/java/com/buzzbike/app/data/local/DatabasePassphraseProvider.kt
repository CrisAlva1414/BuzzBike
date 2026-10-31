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
         * The returned array is the caller-owned clear-text copy. A transient
         * clear-text copy is created while re-sealing a legacy blob, but it is
         * zeroed before this method returns. The returned array is deliberately
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
            try {
                val encoded = Base64.encodeToString(seal(passphrase), Base64.NO_WRAP)
                check(preferences.edit().putString(KEY_SEALED_PASSPHRASE, encoded).commit()) {
                    "failed to persist the sealed database passphrase"
                }
                return passphrase
            } catch (expected: Exception) {
                // The generated value is never returned to the caller and the
                // sealed blob was not persisted (or failed to persist): wipe it
                // so a failed seal/commit does not leave a key resident.
                passphrase.fill(0)
                throw expected
            }
        }

        private fun unsealOrFail(encoded: String): ByteArray {
            val sealed =
                try {
                    Base64.decode(encoded, Base64.NO_WRAP)
                } catch (expected: IllegalArgumentException) {
                    throw IllegalStateException(CORRUPT_MESSAGE)
                }
            val unsealed =
                try {
                    sealer.unseal(sealed, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)
                } catch (expected: IllegalArgumentException) {
                    throw IllegalStateException(CORRUPT_MESSAGE)
                }
            if (unsealed.wasLegacy) {
                // Availability over migration: the original blob already
                // decrypted, so a failed re-seal must not make the database
                // unreadable. Any failure while sealing or verifying the new
                // blob (Keystore, provider, algorithm or round-trip errors) is
                // swallowed here; the legacy blob is left untouched and the
                // migration is retried on the next getPassphrase() call.
                try {
                    resealOrFail(unsealed.plaintext)
                } catch (ignored: Exception) {
                    // Keep serving the legacy plaintext; nothing was written.
                }
            }
            return unsealed.plaintext
        }

        /**
         * Re-seals a legacy blob after proving the new blob round-trips.
         *
         * The legacy blob is the only copy of the passphrase: if the freshly
         * sealed blob cannot be unsealed back to the same plaintext, persisting
         * it would make every stored ride unreadable. Verification therefore
         * happens before the write, and the blob is only replaced once the
         * round-trip proves the new value is readable.
         *
         * The plaintext produced by the verification unseal is a transient
         * clear-text copy: it is zeroed in a `finally` block right after the
         * comparison, so it does not outlive this call.
         *
         * @throws IllegalStateException when the round-trip fails or the new
         *   blob cannot be persisted; the caller keeps using the plaintext it
         *   already decrypted from the untouched legacy blob.
         */
        private fun resealOrFail(plaintext: ByteArray) {
            val resealed =
                try {
                    seal(plaintext)
                } catch (expected: IllegalArgumentException) {
                    null
                }
            val roundTripped =
                try {
                    resealed?.let {
                        sealer.unseal(it, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)
                    }
                } catch (expected: IllegalArgumentException) {
                    null
                }
            val encoded = resealed?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            val verified =
                try {
                    encoded != null &&
                        roundTripped != null &&
                        !roundTripped.wasLegacy &&
                        roundTripped.plaintext.contentEquals(plaintext)
                } finally {
                    roundTripped?.plaintext?.fill(0)
                }
            check(verified && preferences.edit().putString(KEY_SEALED_PASSPHRASE, encoded).commit()) {
                RESEAL_MISMATCH_MESSAGE
            }
        }

        private fun seal(plaintext: ByteArray): ByteArray =
            sealer.seal(plaintext, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)

        private companion object {
            const val PREFERENCES_NAME = "db_secrets_v1"
            const val KEY_SEALED_PASSPHRASE = "passphrase_sealed"

            /** 256 bits of entropy for the SQLCipher key. */
            const val PASSPHRASE_BYTES = 32
            const val CORRUPT_MESSAGE = "database passphrase is corrupt"
            const val RESEAL_MISMATCH_MESSAGE = "re-sealed database passphrase failed round-trip verification"
            val SECURE_RANDOM = SecureRandom()
        }
    }
