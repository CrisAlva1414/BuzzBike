package com.buzzbike.app.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.buzzbike.app.domain.sync.SecretSealer
import com.buzzbike.app.domain.sync.UnsealedSecret
import java.nio.charset.StandardCharsets
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
 * The caller-supplied purpose is authenticated as AES-GCM associated data, so
 * a blob sealed for the database passphrase cannot be opened as the HMAC
 * secret, or vice versa. Both purposes can share the same keystore key because
 * the AAD — not the key — separates them.
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
        override fun seal(
            plaintext: ByteArray,
            purpose: String,
        ): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(associatedData(purpose))
            val iv = cipher.iv
            require(iv.size <= MAX_IV_BYTES) { "GCM IV does not fit the length prefix" }
            val encrypted = cipher.doFinal(plaintext)
            val sealed = ByteArray(IV_LENGTH_PREFIX_BYTES + iv.size + encrypted.size)
            sealed[0] = iv.size.toByte()
            iv.copyInto(sealed, destinationOffset = IV_LENGTH_PREFIX_BYTES)
            encrypted.copyInto(sealed, destinationOffset = IV_LENGTH_PREFIX_BYTES + iv.size)
            return sealed
        }

        override fun unseal(
            sealed: ByteArray,
            purpose: String,
        ): UnsealedSecret {
            val ivLength =
                ivLengthOrNull(sealed)
                    ?: throw IllegalArgumentException(CORRUPT_MESSAGE)
            val iv = sealed.copyOfRange(IV_LENGTH_PREFIX_BYTES, IV_LENGTH_PREFIX_BYTES + ivLength)
            val ciphertext = sealed.copyOfRange(IV_LENGTH_PREFIX_BYTES + ivLength, sealed.size)
            val aad = associatedData(purpose)

            // Current format: the purpose is authenticated. If that fails, the
            // blob predates purpose AAD and is decrypted through the legacy
            // path; the caller then re-seals it with AAD.
            val current =
                try {
                    decrypt(iv, ciphertext, aad)
                } catch (expected: Exception) {
                    null
                }
            if (current != null) return UnsealedSecret(current, wasLegacy = false)

            val legacy =
                try {
                    decrypt(iv, ciphertext, associatedData = null)
                } catch (expected: Exception) {
                    // Never include the blob, the IV or the underlying cause in
                    // the message: it reaches callers and potentially logs.
                    throw IllegalArgumentException(CORRUPT_MESSAGE)
                }
            return UnsealedSecret(legacy, wasLegacy = true)
        }

        private fun decrypt(
            iv: ByteArray,
            ciphertext: ByteArray,
            associatedData: ByteArray?,
        ): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, iv),
            )
            if (associatedData != null) cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        }

        /**
         * Converts [purpose] into AAD bytes.
         *
         * Purposes are internal constants, but reject a blank or oversized
         * value so a future caller cannot accidentally seal with no binding or
         * unbound AAD growth.
         */
        private fun associatedData(purpose: String): ByteArray {
            require(purpose.isNotBlank() && purpose.length <= MAX_PURPOSE_CHARS) {
                "invalid secret purpose"
            }
            return purpose.toByteArray(StandardCharsets.UTF_8)
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

            const val MAX_PURPOSE_CHARS = 64
            const val CORRUPT_MESSAGE = "sealed secret is corrupt"
            val KEYSTORE_LOCK = Any()
        }
    }
