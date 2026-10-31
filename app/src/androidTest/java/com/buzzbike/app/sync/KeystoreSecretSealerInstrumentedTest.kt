package com.buzzbike.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzzbike.app.domain.sync.SecretSealer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * Device-only proof that [AndroidKeystoreSecretSealer] binds the purpose as
 * AES-GCM associated data (ADR-003).
 *
 * The JVM suite fakes the sealer because `AndroidKeyStore` does not exist
 * there; this test exercises the real keystore key. It also builds a legacy
 * blob with the same key but no AAD to prove the transparent migration path.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretSealerInstrumentedTest {
    private val sealer = AndroidKeystoreSecretSealer()

    @Test
    fun given_blobWithPurposeAad_when_unsealWithSamePurpose_thenReturnsPlaintext() {
        val sealed = sealer.seal(SECRET, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)

        val unsealed = sealer.unseal(sealed, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)

        assertFalse(unsealed.wasLegacy)
        assertArrayEquals(SECRET, unsealed.plaintext)
    }

    @Test
    fun given_blobWithPurposeAad_when_unsealWithDifferentPurpose_thenFails() {
        val sealed = sealer.seal(SECRET, SecretSealer.PURPOSE_HMAC_SECRET)

        assertThrows(IllegalArgumentException::class.java) {
            sealer.unseal(sealed, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)
        }
    }

    @Test
    fun given_legacyBlobWithoutAad_when_unseal_thenReadsAndReportsLegacy() {
        val legacy = sealWithoutPurposeAad(SECRET)

        val unsealed = sealer.unseal(legacy, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)

        assertTrue("a blob without AAD must be reported as legacy", unsealed.wasLegacy)
        assertArrayEquals(SECRET, unsealed.plaintext)

        val resealed = sealer.seal(unsealed.plaintext, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)
        val reopened = sealer.unseal(resealed, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)
        assertFalse("re-sealed blob must be current, not legacy", reopened.wasLegacy)
        assertArrayEquals(SECRET, reopened.plaintext)
    }

    @Test
    fun given_databaseBlob_when_unsealAsHmacSecret_thenFails() {
        val sealed = sealer.seal(SECRET, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)

        assertThrows(IllegalArgumentException::class.java) {
            sealer.unseal(sealed, SecretSealer.PURPOSE_HMAC_SECRET)
        }
    }

    @Test
    fun given_twoSealsOfTheSamePlaintext_when_compared_thenIvsDifferAndBothOpen() {
        val first = sealer.seal(SECRET, SecretSealer.PURPOSE_HMAC_SECRET)
        val second = sealer.seal(SECRET, SecretSealer.PURPOSE_HMAC_SECRET)

        assertFalse("the keystore must generate a fresh IV per seal", first.contentEquals(second))
        assertFalse("IVs must not be reused", ivOf(first).contentEquals(ivOf(second)))
        assertArrayEquals(
            SECRET,
            sealer.unseal(first, SecretSealer.PURPOSE_HMAC_SECRET).plaintext,
        )
        assertArrayEquals(
            SECRET,
            sealer.unseal(second, SecretSealer.PURPOSE_HMAC_SECRET).plaintext,
        )
    }

    @Test
    fun given_malformedBlobs_when_unseal_thenFailsClosedWithoutLeaking() {
        val malformed =
            listOf(
                ByteArray(0),
                byteArrayOf(0),
                byteArrayOf(1),
                // Declares an IV length larger than the body.
                byteArrayOf(200.toByte(), 1, 2, 3),
                // Valid IV length but no room for the GCM tag.
                byteArrayOf(12, 1, 2, 3),
                ByteArray(64) { index -> (index * 31).toByte() },
            )

        malformed.forEach { blob ->
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    sealer.unseal(blob, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)
                }
            assertEquals("sealed secret is corrupt", error.message)
        }
    }

    /** Extracts the IV from the `[ivLen:1][iv][ciphertext+tag]` layout. */
    private fun ivOf(blob: ByteArray): ByteArray {
        val ivLength = blob[0].toInt() and 0xFF
        return blob.copyOfRange(IV_LENGTH_PREFIX_BYTES, IV_LENGTH_PREFIX_BYTES + ivLength)
    }

    /**
     * Encrypts [plaintext] with the production keystore key but without AAD,
     * reproducing the blob layout written before purpose separation existed.
     */
    private fun sealWithoutPurposeAad(plaintext: ByteArray): ByteArray {
        // Make sure the production sealer has created the key before reusing
        // the same alias from this test.
        sealer.seal(ByteArray(1), SecretSealer.PURPOSE_DATABASE_PASSPHRASE)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key =
            requireNotNull(keyStore.getKey(KEY_ALIAS, null) as? SecretKey) {
                "production keystore key is missing"
            }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext)
        return ByteArray(IV_LENGTH_PREFIX_BYTES + iv.size + encrypted.size).also { blob ->
            blob[0] = iv.size.toByte()
            iv.copyInto(blob, destinationOffset = IV_LENGTH_PREFIX_BYTES)
            encrypted.copyInto(blob, destinationOffset = IV_LENGTH_PREFIX_BYTES + iv.size)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Must match [AndroidKeystoreSecretSealer]'s private alias. */
        const val KEY_ALIAS = "buzzbike_secret_sealer_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_PREFIX_BYTES = 1

        val SECRET = ByteArray(32) { index -> (index + 1).toByte() }
    }
}
