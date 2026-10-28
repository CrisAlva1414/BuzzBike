package com.buzzbike.app.domain.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [HmacSecretManager].
 *
 * The sealer and storage are fakes so the assertions can inspect the persisted
 * blob directly. The inverting sealer guarantees a stored blob differs from the
 * plaintext while staying reversible for round-trip checks.
 */
class HmacSecretManagerTest {
    private val sealer = InvertingSecretSealer()
    private val storage = InMemorySecretStorage()
    private val manager = HmacSecretManager(sealer, storage)

    @Test
    fun given_secretOfAtLeast32Bytes_when_import_then_successAndSealedBlobStored() {
        val secret = secretOf(SECRET_BYTES, START_BYTE)
        val original = secret.copyOf()

        val result = manager.importSecret(secret)

        assertEquals(ImportResult.Success, result)
        assertTrue(manager.hasSecret())
        val stored = storage.readSealed()
        assertNotNull(stored)
        assertFalse(stored.contentEquals(original))
        assertTrue(stored.contentEquals(sealer.seal(original)))
    }

    @Test
    fun given_secretOf31Bytes_when_import_then_tooShortAndNothingStored() {
        val result = manager.importSecret(secretOf(SECRET_BYTES - 1, START_BYTE))

        assertEquals(ImportResult.TooShort, result)
        assertFalse(manager.hasSecret())
        assertNull(storage.readSealed())
    }

    @Test
    fun given_importedSecret_when_clear_then_hasSecretFalse() {
        manager.importSecret(secretOf(SECRET_BYTES, START_BYTE))

        manager.clear()

        assertFalse(manager.hasSecret())
        assertNull(storage.readSealed())
    }

    @Test
    fun given_twoImports_when_importTwice_then_lastSecretWins() {
        manager.importSecret(secretOf(SECRET_BYTES, FIRST_BYTE))

        manager.importSecret(secretOf(SECRET_BYTES, SECOND_BYTE))

        val stored = storage.readSealed()
        assertNotNull(stored)
        assertTrue(stored.contentEquals(sealer.seal(secretOf(SECRET_BYTES, SECOND_BYTE))))
    }

    @Test
    fun given_secretArray_when_import_then_inputArrayIsZeroed() {
        val secret = secretOf(SECRET_BYTES, START_BYTE)

        manager.importSecret(secret)

        assertArrayEquals(ByteArray(SECRET_BYTES), secret)
    }

    private companion object {
        const val SECRET_BYTES = 32
        const val START_BYTE = 3
        const val FIRST_BYTE = 7
        const val SECOND_BYTE = 9

        fun secretOf(
            size: Int,
            start: Int,
        ): ByteArray = ByteArray(size) { index -> (start + index).toByte() }
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
