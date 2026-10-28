package com.buzzbike.app.domain.sync

/**
 * Seals arbitrary bytes with a device-bound key (ADR-003 envelope encryption).
 *
 * The HMAC secret the user provisions is **not** stored in clear: it is sealed
 * here and only the sealed blob is persisted by [SecretStorage]. The sealing
 * key never leaves the platform keystore, so a copied blob is useless on
 * another device or after the keystore entry is lost.
 *
 * This port lives in `domain/` so the manager can be tested on the JVM; the
 * Android Keystore implementation lives in `sync/` (AGENTS.md §3).
 */
interface SecretSealer {
    /**
     * Returns a self-contained sealed blob ([plaintext] encrypted plus the
     * material needed to decrypt it, such as the IV).
     */
    fun seal(plaintext: ByteArray): ByteArray

    /**
     * Reverses [seal].
     *
     * Implementations must fail closed with an `IllegalArgumentException`
     * whose message carries no secret material when the blob is malformed or
     * was sealed with a different key.
     */
    fun unseal(sealed: ByteArray): ByteArray
}
