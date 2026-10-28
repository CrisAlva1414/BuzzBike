package com.buzzbike.app.domain.sync

/** Outcome of [HmacSecretManager.importSecret]. */
sealed interface ImportResult {
    /** The secret was sealed and persisted. */
    data object Success : ImportResult

    /** The provided secret is below [HmacSecretManager.MIN_SECRET_BYTES]. */
    data object TooShort : ImportResult

    /** Sealing or persisting failed; anything previously stored is untouched. */
    data object Failure : ImportResult
}

/**
 * Provisions and clears the HMAC secret used for Hermes sync (ADR-003).
 *
 * The secret is created by the user on the NAS side and pasted into the app;
 * this class never generates one. It seals the bytes through [SecretSealer]
 * and persists only the resulting blob via [SecretStorage], so plaintext never
 * reaches disk.
 *
 * Pure domain logic: no Android imports, fully testable on the JVM.
 */
class HmacSecretManager(
    private val sealer: SecretSealer,
    private val storage: SecretStorage,
) {
    /**
     * Seals [secret] and stores it, replacing any previous secret.
     *
     * The caller's array is zeroed in a `finally` block (best effort) so the
     * plaintext does not stay in memory longer than necessary — even when the
     * input is rejected as too short. The secret is never logged.
     */
    fun importSecret(secret: ByteArray): ImportResult =
        try {
            if (secret.size < MIN_SECRET_BYTES) {
                ImportResult.TooShort
            } else {
                persist(secret)
            }
        } finally {
            secret.fill(0)
        }

    /** Returns `true` when a sealed secret is stored. */
    fun hasSecret(): Boolean = storage.readSealed() != null

    /** Removes the stored secret, disabling authenticated sync. */
    fun clear() {
        storage.clear()
    }

    private fun persist(secret: ByteArray): ImportResult =
        try {
            storage.writeSealed(sealer.seal(secret))
            ImportResult.Success
        } catch (expected: Exception) {
            ImportResult.Failure
        }

    companion object {
        /**
         * Minimum accepted secret length. HMAC-SHA256 keys shorter than the
         * 256-bit output weaken forgery resistance, so anything below 32 bytes
         * is rejected instead of silently accepted.
         */
        const val MIN_SECRET_BYTES = 32
    }
}
