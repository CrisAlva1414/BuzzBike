package com.buzzbike.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.domain.sync.SecretSealer
import com.buzzbike.app.domain.sync.UnsealedSecret
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyStoreException

/**
 * JVM tests for [DatabasePassphraseProvider] and [LegacyDatabaseCleaner].
 *
 * The passphrase is the key to every stored GPS coordinate: these tests pin
 * that it is generated with 32 random bytes, only ever persisted sealed, and
 * that a corrupt blob fails closed instead of silently re-keying the database.
 *
 * The real SQLCipher open path is not exercised here — the native library only
 * exists on a device, so encrypted open/close needs instrumentation tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabasePassphraseProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
        deleteDatabaseFiles()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
        deleteDatabaseFiles()
    }

    @Test
    fun given_noStoredPassphrase_when_getPassphrase_then_generatesAndPersistsOnlyTheSealedBlob() {
        val sealer = XorSecretSealer()
        val provider = DatabasePassphraseProvider(context, sealer)

        val passphrase = provider.getPassphrase()

        assertEquals(PASSPHRASE_BYTES, passphrase.size)
        val encoded = preferences.getString(KEY_SEALED_PASSPHRASE, null)
        assertNotNull("sealed blob was not persisted", encoded)
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        assertFalse("sealed blob must differ from the passphrase", sealed.contentEquals(passphrase))
        assertArrayEquals(
            "stored blob must unseal to the generated passphrase",
            passphrase,
            sealer.unseal(sealed, SecretSealer.PURPOSE_DATABASE_PASSPHRASE).plaintext,
        )
    }

    @Test
    fun given_sealFails_when_getPassphrase_then_generatedPassphraseIsZeroedAndNothingPersisted() {
        val sealer = CapturingThrowingSealer()
        val provider = DatabasePassphraseProvider(context, sealer)

        assertThrows(KeyStoreException::class.java) { provider.getPassphrase() }

        val captured = requireNotNull(sealer.captured)
        assertTrue("a failed seal must wipe the generated passphrase", captured.all { it == 0.toByte() })
        assertNull(
            "a failed seal must not persist a blob",
            preferences.getString(KEY_SEALED_PASSPHRASE, null),
        )
    }

    @Test
    fun given_storedPassphrase_when_newProviderReads_then_returnsTheSamePassphrase() {
        val sealer = XorSecretSealer()
        val first = DatabasePassphraseProvider(context, sealer).getPassphrase()

        val second = DatabasePassphraseProvider(context, sealer).getPassphrase()

        assertArrayEquals(first, second)
    }

    @Test
    fun given_generatedPassphrase_when_readFromDisk_then_clearValueIsNowhere() {
        val passphrase = DatabasePassphraseProvider(context, XorSecretSealer()).getPassphrase()

        val encoded = requireNotNull(preferences.getString(KEY_SEALED_PASSPHRASE, null))
        val fromDisk = Base64.decode(encoded, Base64.NO_WRAP)
        val base64Passphrase = Base64.encodeToString(passphrase, Base64.NO_WRAP)

        assertFalse("passphrase was persisted as plaintext Base64", encoded == base64Passphrase)
        assertFalse("passphrase was persisted as raw bytes", fromDisk.contentEquals(passphrase))
    }

    @Test
    fun given_corruptSealedBlob_when_getPassphrase_then_failsClosedWithoutRegenerating() {
        val encoded = Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP)
        preferences.edit().putString(KEY_SEALED_PASSPHRASE, encoded).commit()
        val provider = DatabasePassphraseProvider(context, RejectingSecretSealer())

        assertThrows(IllegalStateException::class.java) { provider.getPassphrase() }

        assertEquals(
            "a failed unseal must not replace the stored blob",
            encoded,
            preferences.getString(KEY_SEALED_PASSPHRASE, null),
        )
    }

    @Test
    fun given_legacyBlobWithoutPurposeAad_when_getPassphrase_thenReturnsItAndReseals() {
        val sealer = XorSecretSealer()
        val passphrase = ByteArray(PASSPHRASE_BYTES) { index -> (index + 1).toByte() }
        val legacy = Base64.encodeToString(sealer.sealLegacy(passphrase), Base64.NO_WRAP)
        preferences.edit().putString(KEY_SEALED_PASSPHRASE, legacy).commit()

        val result = DatabasePassphraseProvider(context, sealer).getPassphrase()

        assertArrayEquals(passphrase, result)
        val resealed = Base64.decode(requireNotNull(preferences.getString(KEY_SEALED_PASSPHRASE, null)), Base64.NO_WRAP)
        val unsealed = sealer.unseal(resealed, SecretSealer.PURPOSE_DATABASE_PASSPHRASE)
        assertFalse("legacy blob must be re-sealed with purpose AAD", unsealed.wasLegacy)
        assertArrayEquals(passphrase, unsealed.plaintext)
    }

    @Test
    fun given_legacyBlob_when_resealedBlobFailsRoundTrip_thenReturnsPlaintextAndKeepsOriginalBlob() {
        val sealer = BrokenRoundTripSecretSealer()
        val passphrase = ByteArray(PASSPHRASE_BYTES) { index -> (index + 1).toByte() }
        val legacy = Base64.encodeToString(sealer.sealLegacy(passphrase), Base64.NO_WRAP)
        preferences.edit().putString(KEY_SEALED_PASSPHRASE, legacy).commit()

        val result = DatabasePassphraseProvider(context, sealer).getPassphrase()

        assertArrayEquals("the already-decrypted legacy passphrase must stay usable", passphrase, result)
        assertEquals(
            "a re-seal that does not round-trip must not replace the stored blob",
            legacy,
            preferences.getString(KEY_SEALED_PASSPHRASE, null),
        )
    }

    @Test
    fun given_legacyBlob_when_resealThrowsKeystoreException_thenReturnsPlaintextAndKeepsOriginalBlob() {
        val sealer = ThrowingSealSecretSealer()
        val passphrase = ByteArray(PASSPHRASE_BYTES) { index -> (index + 1).toByte() }
        val legacy = Base64.encodeToString(sealer.sealLegacy(passphrase), Base64.NO_WRAP)
        preferences.edit().putString(KEY_SEALED_PASSPHRASE, legacy).commit()

        val result = DatabasePassphraseProvider(context, sealer).getPassphrase()

        assertArrayEquals("the already-decrypted legacy passphrase must stay usable", passphrase, result)
        assertEquals(
            "a Keystore failure while re-sealing must not replace the stored blob",
            legacy,
            preferences.getString(KEY_SEALED_PASSPHRASE, null),
        )
    }

    @Test
    fun given_hmacPurposeBlob_when_getPassphrase_thenFailsClosed() {
        val sealer = XorSecretSealer()
        val encoded =
            Base64.encodeToString(
                sealer.seal(ByteArray(PASSPHRASE_BYTES), SecretSealer.PURPOSE_HMAC_SECRET),
                Base64.NO_WRAP,
            )
        preferences.edit().putString(KEY_SEALED_PASSPHRASE, encoded).commit()
        val provider = DatabasePassphraseProvider(context, sealer)

        assertThrows(IllegalStateException::class.java) { provider.getPassphrase() }

        assertEquals(
            "a cross-purpose blob must not be replaced",
            encoded,
            preferences.getString(KEY_SEALED_PASSPHRASE, null),
        )
    }

    @Test
    fun given_onlyPlaintextDatabase_when_clean_then_deletesDatabaseAndSidecars() {
        val files = createLegacyDatabaseFiles()

        LegacyDatabaseCleaner.deletePlaintextDatabaseIfNeeded(context)

        assertTrue("plaintext database must be deleted", files.none { it.exists() })
    }

    @Test
    fun given_plaintextAndEncryptedDatabases_when_clean_then_deletesPlaintextAndKeepsEncrypted() {
        val encrypted = createDatabaseFile(ENCRYPTED_DATABASE_NAME)
        val plaintextFiles = createLegacyDatabaseFiles()

        LegacyDatabaseCleaner.deletePlaintextDatabaseIfNeeded(context)

        assertTrue("plaintext database must not survive next to the encrypted one", plaintextFiles.none { it.exists() })
        assertTrue("encrypted database must be kept", encrypted.exists())
    }

    @Test
    fun given_noDatabase_when_clean_then_doesNothing() {
        LegacyDatabaseCleaner.deletePlaintextDatabaseIfNeeded(context)

        assertFalse(
            "cleanup without a plaintext database must be a no-op",
            context.getDatabasePath(LEGACY_DATABASE_NAME).exists(),
        )
        assertFalse(
            "cleanup must not create the encrypted database",
            context.getDatabasePath(ENCRYPTED_DATABASE_NAME).exists(),
        )
    }

    private fun createLegacyDatabaseFiles(): List<File> =
        listOf(
            createDatabaseFile(LEGACY_DATABASE_NAME),
            createDatabaseFile("$LEGACY_DATABASE_NAME-wal"),
            createDatabaseFile("$LEGACY_DATABASE_NAME-shm"),
        )

    private fun createDatabaseFile(name: String): File {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.writeText("not a real database")
        return file
    }

    private fun deleteDatabaseFiles() {
        val names = listOf(ENCRYPTED_DATABASE_NAME, LEGACY_DATABASE_NAME)
        names
            .flatMap { name -> listOf(name, "$name-wal", "$name-shm") }
            .forEach { name -> context.getDatabasePath(name).delete() }
    }

    private companion object {
        const val PREFERENCES_NAME = "db_secrets_v1"
        const val KEY_SEALED_PASSPHRASE = "passphrase_sealed"
        const val PASSPHRASE_BYTES = 32
        const val ENCRYPTED_DATABASE_NAME = "buzzbike-encrypted.db"
        const val LEGACY_DATABASE_NAME = "buzzbike.db"
    }
}

/**
 * Reversible fake that binds the purpose into the blob, mimicking AES-GCM AAD.
 *
 * Current layout: `[1][purposeLength][purpose][xor plaintext]`.
 * Legacy layout: `[0][xor plaintext]`, i.e. a blob written before this change.
 */
private class XorSecretSealer : SecretSealer {
    override fun seal(
        plaintext: ByteArray,
        purpose: String,
    ): ByteArray {
        val purposeBytes = purpose.toByteArray(Charsets.UTF_8)
        return byteArrayOf(CURRENT_MARKER, purposeBytes.size.toByte()) +
            purposeBytes +
            xor(plaintext)
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
                val storedPurpose = sealed.copyOfRange(CURRENT_HEADER_BYTES, purposeEnd).toString(Charsets.UTF_8)
                require(storedPurpose == purpose) { "blob was sealed for another purpose" }
                UnsealedSecret(xor(sealed.copyOfRange(purposeEnd, sealed.size)), wasLegacy = false)
            }

            LEGACY_MARKER -> UnsealedSecret(xor(sealed.copyOfRange(1, sealed.size)), wasLegacy = true)

            else -> throw IllegalArgumentException("corrupt blob")
        }
    }

    /** Builds a pre-AAD blob, as an old app version would have written it. */
    fun sealLegacy(plaintext: ByteArray): ByteArray = byteArrayOf(LEGACY_MARKER) + xor(plaintext)

    private fun xor(bytes: ByteArray): ByteArray =
        ByteArray(bytes.size) { index -> (bytes[index].toInt() xor XOR_MASK).toByte() }

    private companion object {
        const val XOR_MASK = 0x5A
        const val CURRENT_HEADER_BYTES = 2
        const val CURRENT_MARKER: Byte = 1
        const val LEGACY_MARKER: Byte = 0
    }
}

/**
 * Seals each blob after flipping the first plaintext byte, so a re-sealed blob
 * never unseals back to the original value. Legacy blobs still round-trip.
 */
private class BrokenRoundTripSecretSealer : SecretSealer {
    private val delegate = XorSecretSealer()

    override fun seal(
        plaintext: ByteArray,
        purpose: String,
    ): ByteArray {
        val corrupted = plaintext.copyOf()
        if (corrupted.isNotEmpty()) {
            corrupted[0] = (corrupted[0].toInt() xor 0xFF).toByte()
        }
        return delegate.seal(corrupted, purpose)
    }

    override fun unseal(
        sealed: ByteArray,
        purpose: String,
    ): UnsealedSecret = delegate.unseal(sealed, purpose)

    /** Builds a pre-AAD blob, as an old app version would have written it. */
    fun sealLegacy(plaintext: ByteArray): ByteArray = delegate.sealLegacy(plaintext)
}

/** Mimics a blob sealed with a different (lost) key. */
private class RejectingSecretSealer : SecretSealer {
    override fun seal(
        plaintext: ByteArray,
        purpose: String,
    ): ByteArray = plaintext.copyOf()

    override fun unseal(
        sealed: ByteArray,
        purpose: String,
    ): UnsealedSecret = throw IllegalArgumentException("corrupt blob")
}

/**
 * Legacy blobs still unseal, but any attempt to re-seal fails with a Keystore
 * exception, as a locked or reset Android Keystore would.
 */
private class ThrowingSealSecretSealer : SecretSealer {
    private val delegate = XorSecretSealer()

    override fun seal(
        plaintext: ByteArray,
        purpose: String,
    ): ByteArray = throw KeyStoreException("keystore unavailable")

    override fun unseal(
        sealed: ByteArray,
        purpose: String,
    ): UnsealedSecret = delegate.unseal(sealed, purpose)

    /** Builds a pre-AAD blob, as an old app version would have written it. */
    fun sealLegacy(plaintext: ByteArray): ByteArray = delegate.sealLegacy(plaintext)
}

/** Captures the generated passphrase and fails while sealing it. */
private class CapturingThrowingSealer : SecretSealer {
    var captured: ByteArray? = null

    override fun seal(
        plaintext: ByteArray,
        purpose: String,
    ): ByteArray {
        captured = plaintext
        throw KeyStoreException("keystore unavailable")
    }

    override fun unseal(
        sealed: ByteArray,
        purpose: String,
    ): UnsealedSecret = throw IllegalArgumentException("not used")
}
