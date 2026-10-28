package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.HmacSecretMissingException
import com.buzzbike.app.domain.sync.SecretSealer
import com.buzzbike.app.domain.sync.SecretStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JVM tests for [StoredHmacKeyProvider].
 *
 * They pin the import path: the key handed to the signer must be exactly the
 * user-provisioned secret, and a missing secret must fail closed instead of
 * silently generating a key the Hermes receiver cannot verify.
 */
class StoredHmacKeyProviderTest {
    private val sealer = InvertingSecretSealer()
    private val storage = InMemorySecretStorage()
    private val manager = HmacSecretManager(sealer, storage)
    private val provider = StoredHmacKeyProvider(sealer, storage)

    @Test
    fun given_importedSecret_when_getOrCreateKey_then_returnsImportedSecret() {
        val secret = secretOf(SECRET_BYTES)
        manager.importSecret(secret.copyOf())

        val key = provider.getOrCreateKey()

        assertEquals(ALGORITHM, key.algorithm)
        assertArrayEquals(secret, key.encoded)
    }

    @Test
    fun given_noSecret_when_getOrCreateKey_then_throwsMissingException() {
        assertThrows(HmacSecretMissingException::class.java) { provider.getOrCreateKey() }
    }

    @Test
    fun given_importedSecret_when_sign_then_matchesIndependentMac() {
        manager.importSecret(secretOf(SECRET_BYTES))

        val actual = hmac(provider.getOrCreateKey(), DATA)
        val expected = hmac(SecretKeySpec(secretOf(SECRET_BYTES), ALGORITHM), DATA)

        assertArrayEquals(expected, actual)
    }

    private fun hmac(
        key: javax.crypto.SecretKey,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance(ALGORITHM).run {
            init(key)
            doFinal(data)
        }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val SECRET_BYTES = 32

        val DATA = """{"rideId":"ride-123","distanceM":1234.5}""".toByteArray(StandardCharsets.UTF_8)

        fun secretOf(size: Int): ByteArray = ByteArray(size) { index -> (index + 1).toByte() }
    }
}

/** Reversible fake: `seal` and `unseal` both invert every byte. */
private class InvertingSecretSealer : SecretSealer {
    override fun seal(plaintext: ByteArray): ByteArray =
        plaintext.map { byte -> (byte.toInt() xor XOR_MASK).toByte() }.toByteArray()

    override fun unseal(sealed: ByteArray): ByteArray = seal(sealed)

    private companion object {
        const val XOR_MASK = 0xFF
    }
}

/** In-memory [SecretStorage] that copies on both read and write. */
private class InMemorySecretStorage : SecretStorage {
    private var sealed: ByteArray? = null

    override fun readSealed(): ByteArray? = sealed?.copyOf()

    override fun writeSealed(sealed: ByteArray) {
        this.sealed = sealed.copyOf()
    }

    override fun clear() {
        sealed = null
    }
}
