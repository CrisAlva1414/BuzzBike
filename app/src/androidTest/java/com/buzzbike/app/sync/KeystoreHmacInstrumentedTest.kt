package com.buzzbike.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.ImportResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only proof of the Keystore envelope path (ADR-003).
 *
 * The JVM suite fakes the sealer because `AndroidKeyStore` does not exist
 * there. This test imports a 32-byte secret, seals it with a real keystore
 * AES-GCM key, signs a payload through the stored key and verifies it.
 * Nothing secret is logged or asserted on: only the outcome and the hex
 * length of the signature.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreHmacInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sealer = AndroidKeystoreSecretSealer()
    private val storage = SharedPrefsSecretStorage(context)
    private val secretManager = HmacSecretManager(sealer, storage)

    @Before
    fun setUp() {
        secretManager.clear()
    }

    @After
    fun tearDown() {
        secretManager.clear()
    }

    @Test
    fun given_importedSecret_when_signed_thenVerifiesAndTamperedPayloadFails() {
        val imported = secretManager.importSecret(ByteArray(SECRET_BYTES) { index -> (index + 1).toByte() })
        assertEquals(ImportResult.Success, imported)

        val signer = HmacSha256Signer(StoredHmacKeyProvider(sealer, storage))
        val payload = PAYLOAD.toByteArray(Charsets.UTF_8)
        val signature = signer.sign(TIMESTAMP_SECONDS, payload)

        assertTrue(signer.verify(TIMESTAMP_SECONDS, payload, signature))
        assertEquals(SIGNATURE_HEX_CHARS, signature.length)
        assertTrue(signature.all { char -> char in '0'..'9' || char in 'a'..'f' })

        val tampered = TAMPERED_PAYLOAD.toByteArray(Charsets.UTF_8)
        assertFalse(signer.verify(TIMESTAMP_SECONDS, tampered, signature))
    }

    companion object {
        const val SECRET_BYTES = 32
        const val TIMESTAMP_SECONDS = 1_700_000_000L
        const val PAYLOAD = "hello"
        const val TAMPERED_PAYLOAD = "hellp"

        /** HMAC-SHA256 produces 32 bytes, i.e. 64 hex characters. */
        const val SIGNATURE_HEX_CHARS = 64
    }
}
