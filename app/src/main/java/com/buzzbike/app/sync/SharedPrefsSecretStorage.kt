package com.buzzbike.app.sync

import android.content.Context
import android.util.Base64
import com.buzzbike.app.domain.sync.SecretStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SecretStorage] backed by a private [android.content.SharedPreferences] file.
 *
 * Only the sealed blob is stored, Base64-encoded (`NO_WRAP`). The blob is
 * useless without the AES key held by Android Keystore, so this file leaking
 * does not expose the HMAC secret itself.
 *
 * Writes use `commit()` rather than `apply()`: provisioning the webhook secret
 * is a critical, user-initiated action and the caller must know whether the
 * value reached disk before reporting success. `apply()` is asynchronous and
 * would report success even if the write is later lost.
 */
@Singleton
class SharedPrefsSecretStorage
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : SecretStorage {
        private val preferences =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        override fun readSealed(): ByteArray? = preferences.getString(KEY_SEALED_SECRET, null)?.let(::decodeOrNull)

        override fun writeSealed(sealed: ByteArray) {
            val encoded = Base64.encodeToString(sealed, Base64.NO_WRAP)
            check(preferences.edit().putString(KEY_SEALED_SECRET, encoded).commit()) {
                "failed to persist the sealed HMAC secret"
            }
        }

        override fun clear() {
            check(preferences.edit().remove(KEY_SEALED_SECRET).commit()) {
                "failed to clear the sealed HMAC secret"
            }
        }

        /**
         * Decodes [encoded], treating a malformed value as "no secret".
         *
         * A corrupted entry cannot be unsealed anyway; returning `null` lets
         * the user re-provision instead of crashing every sync pass.
         */
        private fun decodeOrNull(encoded: String): ByteArray? =
            try {
                Base64.decode(encoded, Base64.NO_WRAP)
            } catch (expected: IllegalArgumentException) {
                null
            }

        private companion object {
            const val PREFERENCES_NAME = "sync_secrets_v1"
            const val KEY_SEALED_SECRET = "hmac_secret_sealed"
        }
    }
