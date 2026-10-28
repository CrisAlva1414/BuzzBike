package com.buzzbike.app.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.buzzbike.app.domain.sync.HmacKeyProvider
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HmacKeyProvider] backed by Android Keystore (ADR-003).
 *
 * The secret is generated inside the keystore and never leaves it: this class
 * only handles the [SecretKey] reference and never calls `getEncoded()`, so
 * the raw bytes are not exportable. Losing the keystore (uninstall, factory
 * reset) means the webhook secret must be re-provisioned on the NAS — an
 * accepted consequence of ADR-003.
 *
 * `setUserAuthenticationRequired(false)` is intentional: the outbox worker
 * signs and sends rides in the background, with the screen off. Requiring user
 * authentication per signature would make background sync silently fail; the
 * keystore itself is the protection boundary here.
 */
@Singleton
class AndroidKeystoreHmacKeyProvider
    @Inject
    constructor() : HmacKeyProvider {
        /**
         * Returns the key stored under [KEY_ALIAS], creating it when missing.
         *
         * If the alias exists but does not hold a [SecretKey] (stale entry from
         * another algorithm, corrupted entry), it is deleted and regenerated
         * instead of failing every future sync with a cast error.
         */
        override fun getOrCreateKey(): SecretKey =
            synchronized(KEYSTORE_LOCK) {
                val keyStore = loadKeyStore()
                val existing = keyStore.getKey(KEY_ALIAS, null)
                when {
                    existing is SecretKey -> existing
                    existing != null -> {
                        keyStore.deleteEntry(KEY_ALIAS)
                        generateKey()
                    }
                    else -> generateKey()
                }
            }

        private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        private fun generateKey(): SecretKey {
            val spec =
                KeyGenParameterSpec
                    .Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    ).setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            generator.init(spec)
            return generator.generateKey()
        }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "buzzbike_hmac_v1"
            val KEYSTORE_LOCK = Any()
        }
    }
