package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.buzzbike.app.domain.sync.HmacSecretMissingException
import com.buzzbike.app.domain.sync.SecretSealer
import com.buzzbike.app.domain.sync.SecretStorage
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * [HmacKeyProvider] backed by the user-provisioned secret (ADR-003).
 *
 * Unlike a generated keystore key, the HMAC key here must match the one the
 * Hermes receiver holds, so it is imported by the user and sealed at rest. On
 * every call this provider unseals the blob and rebuilds a [SecretKeySpec];
 * no secret material is cached between calls.
 *
 * When nothing was imported it throws [HmacSecretMissingException] instead of
 * creating a random key: a random key would only ever produce signatures the
 * receiver cannot verify.
 */
class StoredHmacKeyProvider(
    private val sealer: SecretSealer,
    private val storage: SecretStorage,
) : HmacKeyProvider {
    override fun getOrCreateKey(): SecretKey {
        val sealed =
            storage.readSealed()
                ?: throw HmacSecretMissingException()
        val secretBytes = sealer.unseal(sealed)
        try {
            // SecretKeySpec copies the array internally, so zeroing it below
            // does not corrupt the key.
            return SecretKeySpec(secretBytes, ALGORITHM)
        } finally {
            secretBytes.fill(0)
        }
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
