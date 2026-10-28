package com.buzzbike.app.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.buzzbike.app.domain.sync.SecretSealer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SecretSealer] backed by a non-exportable AES-256-GCM key in Android
 * Keystore (ADR-003 envelope encryption).
 *
 * The user-provisioned HMAC secret is sealed with this keystore key; only the
 * sealed blob is persisted. The AES key itself can never be read out of the
 * keystore, so copying the blob to another device does not reveal the secret.
 *
 * Blob layout: `[ivLen:1 byte][iv][ciphertext+tag]`. The random IV is the one
 * the cipher generates for each [seal] call — never reused.
 *
 * `setUserAuthenticationRequired(false)` is intentional: the outbox worker
 * signs and sends rides in the background with the screen off. Requiring user
 * authentication per seal/unseal would make background sync silently fail; the
 * keystore itself remains the protection boundary.
 */
@Singleton
class AndroidKeystoreSecretSealer
    @Inject
    constructor() : SecretSealer {
        override fun seal(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            require(iv.size <= MAX_IV_BYTES) { "GCM IV does not fit the length prefix" }
            val encrypted = cipher.doFinal(plaintext)
            val sealed = ByteArray(IV_LENGTH_PREFIX_BYTES + iv.size + encrypted.size)
            sealed[0] = iv.size.toByte()
            iv.copyInto(sealed, destinationOffset = IV_LENGTH_PREFIX_BYTES)
            encrypted.copyInto(sealed, destinationOffset = IV_LENGTH_PREFIX_BYTES + iv.size)
            return sealed
        }

        override fun unseal(sealed: ByteArray): ByteArray {
            val ivLength =
                ivLengthOrNull(sealed)
                    ?: throw IllegalArgumentException(CORRUPT_MESSAGE)
            val iv = sealed.copyOfRange(IV_LENGTH_PREFIX_BYTES, IV_LENGTH_PREFIX_BYTES + ivLength)
            val ciphertext = sealed.copyOfRange(IV_LENGTH_PREFIX_BYTES + ivLength, sealed.size)
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(TAG_LENGTH_BITS, iv),
                )
                cipher.doFinal(ciphertext)
            } catch (expected: Exception) {
                // Never include the blob, the IV or the underlying cause in the
                // message: it reaches callers and potentially logs.
                throw IllegalArgumentException(CORRUPT_MESSAGE)
            }
        }

        /**
         * Reads and validates the 1-byte IV length prefix.
         *
         * Returns `null` when the blob is too short, the declared length is
         * impossible, or there is no room left for the GCM tag.
         */
        private fun ivLengthOrNull(sealed: ByteArray): Int? {
            if (sealed.size <= IV_LENGTH_PREFIX_BYTES) return null
            val declared = sealed[0].toInt() and BYTE_MASK
            val bodySize = sealed.size - IV_LENGTH_PREFIX_BYTES
            return declared.takeIf { it in 1..MAX_IV_BYTES && bodySize - it >= TAG_LENGTH_BYTES }
        }

        /**
         * Returns the existing [KEY_ALIAS] key, creating it when missing.
         *
         * A stale entry that does not hold a [SecretKey] is deleted and
         * regenerated; such an entry could never decrypt a blob anyway.
         * Synchronised because [KeyStore] access is not documented as
         * thread-safe and the sealer is a singleton.
         */
        private fun getOrCreateKey(): SecretKey =
            synchronized(KEYSTORE_LOCK) {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                when (val existing = keyStore.getKey(KEY_ALIAS, null)) {
                    is SecretKey -> existing
                    null -> generateKey()
                    else -> {
                        keyStore.deleteEntry(KEY_ALIAS)
                        generateKey()
                    }
                }
            }

        private fun generateKey(): SecretKey {
            val spec =
                KeyGenParameterSpec
                    .Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setKeySize(KEY_SIZE_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(spec)
            return generator.generateKey()
        }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "buzzbike_secret_sealer_v1"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val KEY_SIZE_BITS = 256

            /** One byte holds the IV length. */
            const val IV_LENGTH_PREFIX_BYTES = 1
            const val MAX_IV_BYTES = 255
            const val BYTE_MASK = 0xFF

            /** GCM authentication tag length recommended by NIST SP 800-38D. */
            const val TAG_LENGTH_BITS = 128
            const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8

            const val CORRUPT_MESSAGE = "sealed secret is corrupt"
            val KEYSTORE_LOCK = Any()
        }
    }
