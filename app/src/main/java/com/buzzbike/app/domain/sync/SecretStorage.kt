package com.buzzbike.app.domain.sync

/**
 * Persists exactly one sealed secret blob (see [SecretSealer]).
 *
 * The storage only ever sees sealed bytes: it must not decrypt, interpret or
 * log them. A `null` read means "no secret has been provisioned yet", which
 * the sync stack treats as "sync cannot sign" rather than as an empty secret.
 */
interface SecretStorage {
    /** Returns the stored sealed blob, or `null` when nothing is stored. */
    fun readSealed(): ByteArray?

    /**
     * Persists [sealed], replacing any previous value.
     *
     * Implementations must make the write durable before returning; callers
     * rely on a successful return to report [HmacSecretManager.ImportResult.Success].
     */
    fun writeSealed(sealed: ByteArray)

    /** Removes the stored blob; a later [readSealed] returns `null`. */
    fun clear()
}
