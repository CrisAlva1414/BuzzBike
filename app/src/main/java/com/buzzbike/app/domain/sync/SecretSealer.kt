package com.buzzbike.app.domain.sync

/**
 * Seals arbitrary bytes with a device-bound key (ADR-003 envelope encryption).
 *
 * The HMAC secret the user provisions is **not** stored in clear: it is sealed
 * here and only the sealed blob is persisted by [SecretStorage]. The sealing
 * key never leaves the platform keystore, so a copied blob is useless on
 * another device or after the keystore entry is lost.
 *
 * [purpose] is authenticated as AES-GCM associated data. A blob sealed for one
 * purpose (for example the database passphrase) therefore cannot be opened as
 * another (for example the HMAC secret), even though both are protected by the
 * same keystore key.
 *
 * This port lives in `domain/` so the manager can be tested on the JVM; the
 * Android Keystore implementation lives in `sync/` (AGENTS.md §3).
 */
interface SecretSealer {
    /**
     * Returns a self-contained sealed blob ([plaintext] encrypted plus the
     * material needed to decrypt it, such as the IV) bound to [purpose].
     */
    fun seal(
        plaintext: ByteArray,
        purpose: String,
    ): ByteArray

    /**
     * Reverses [seal] for [purpose].
     *
     * Blobs written before purpose AAD existed are still readable: the
     * implementation retries without associated data and reports
     * [UnsealedSecret.wasLegacy] so the caller can re-seal them. Callers must
     * persist the re-sealed blob when that flag is set.
     *
     * Implementations must fail closed with an `IllegalArgumentException`
     * whose message carries no secret material when the blob is malformed or
     * was sealed with a different key or purpose.
     */
    fun unseal(
        sealed: ByteArray,
        purpose: String,
    ): UnsealedSecret

    companion object {
        /** Purpose of the SQLCipher database passphrase. */
        const val PURPOSE_DATABASE_PASSPHRASE = "db-passphrase-v1"

        /** Purpose of the user-provisioned Hermes HMAC secret. */
        const val PURPOSE_HMAC_SECRET = "hmac-secret-v1"
    }
}

/**
 * Result of [SecretSealer.unseal].
 *
 * [wasLegacy] is `true` when the blob did not authenticate a purpose AAD and
 * was decrypted through the pre-AAD compatibility path. The caller owns the
 * [plaintext] array and should re-seal it with the current purpose.
 */
data class UnsealedSecret(
    val plaintext: ByteArray,
    val wasLegacy: Boolean,
)
