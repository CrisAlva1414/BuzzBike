package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.HmacSecretMissingException
import com.buzzbike.app.domain.sync.SecretSealer
import com.buzzbike.app.domain.sync.SecretStorage
import com.buzzbike.app.domain.sync.UnsealedSecret
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun given_legacyBlobWithoutAad_when_getOrCreateKey_thenReturnsSecretAndReseals() {
        val secret = secretOf(SECRET_BYTES)
        storage.writeSealed(sealer.sealLegacy(secret))

        val key = provider.getOrCreateKey()

        assertArrayEquals(secret, key.encoded)
        val stored = requireNotNull(storage.readSealed())
        val unsealed = sealer.unseal(stored, SecretSealer.PURPOSE_HMAC_SECRET)
        assertFalse("legacy blob must be re-sealed with purpose AAD", unsealed.wasLegacy)
        assertArrayEquals(secret, unsealed.plaintext)
        assertNotNull(storage.readSealed())
    }

    @Test
    fun given_databasePurposeBlob_when_getOrCreateKey_thenFailsInsteadOfCrossOpening() {
        storage.writeSealed(
            sealer.seal(secretOf(SECRET_BYTES), SecretSealer.PURPOSE_DATABASE_PASSPHRASE),
        )

        assertThrows(IllegalArgumentException::class.java) { provider.getOrCreateKey() }
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

/**
 * Reversible fake that binds the purpose into the blob, mimicking AES-GCM AAD.
 *
 * Current layout: `[1][purposeLength][purpose][inverted plaintext]`.
 * Legacy layout: `[0][inverted plaintext]`, i.e. a blob written before this
 * change had no purpose binding.
 */
private class InvertingSecretSealer : SecretSealer {
    override fun seal(
        plaintext: ByteArray,
        purpose: String,
    ): ByteArray {
        val purposeBytes = purpose.toByteArray(StandardCharsets.UTF_8)
        return byteArrayOf(CURRENT_MARKER, purposeBytes.size.toByte()) +
            purposeBytes +
            invert(plaintext)
    }

    override fun unseal(
        sealed: ByteArray,
        purpose: String,
    ): UnsealedSecret {
        require(sealed.isNotEmpty()) { "corrupt blob" }
        return when (sealed[0]) {
            CURRENT_MARKER -> {
                val purposeLength = sealed[1].toInt()
                val purposeEnd = CURRENT_HEADER_BYTES + purposeLength
                require(purposeEnd <= sealed.size) { "corrupt blob" }
                val storedPurpose =
                    sealed
                        .copyOfRange(CURRENT_HEADER_BYTES, purposeEnd)
                        .toString(StandardCharsets.UTF_8)
                require(storedPurpose == purpose) { "blob was sealed for another purpose" }
                UnsealedSecret(invert(sealed.copyOfRange(purposeEnd, sealed.size)), wasLegacy = false)
            }

            LEGACY_MARKER -> UnsealedSecret(invert(sealed.copyOfRange(1, sealed.size)), wasLegacy = true)

            else -> throw IllegalArgumentException("corrupt blob")
        }
    }

    /** Builds a pre-AAD blob, as an old app version would have written it. */
    fun sealLegacy(plaintext: ByteArray): ByteArray = byteArrayOf(LEGACY_MARKER) + invert(plaintext)

    private fun invert(bytes: ByteArray): ByteArray =
        bytes.map { byte -> (byte.toInt() xor XOR_MASK).toByte() }.toByteArray()

    private companion object {
        const val XOR_MASK = 0xFF
        const val CURRENT_HEADER_BYTES = 2
        const val CURRENT_MARKER: Byte = 1
        const val LEGACY_MARKER: Byte = 0
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
