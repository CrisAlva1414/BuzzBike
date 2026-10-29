package com.buzzbike.app.data.settings

import android.content.Context
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.SenderType
import com.buzzbike.app.domain.sync.SyncConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncConfigStore] backed by a private [android.content.SharedPreferences] file.
 *
 * Only the non-secret transport configuration lives here: the HMAC secret is
 * sealed separately (see `SharedPrefsSecretStorage`), so this file leaking does
 * not expose signing material. URLs are never logged.
 *
 * Writes use `commit()` rather than `apply()`: configuring sync is an explicit
 * user action, and the caller must know whether the value reached disk before
 * reporting success.
 */
@Singleton
class SharedPrefsSyncConfigStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : SyncConfigStore {
        private val preferences =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        /**
         * Reads the stored configuration.
         *
         * Returns `null` unless both [HermesConfig.endpoint] and
         * [HermesConfig.healthCheckUrl] are present and non-blank: sync has no
         * safe target without either, and the send endpoint is never reused as
         * a health check.
         */
        override fun current(): HermesConfig? {
            val endpoint = preferences.getString(KEY_ENDPOINT, null)?.trim().orEmpty()
            val healthCheckUrl = preferences.getString(KEY_HEALTH_CHECK_URL, null)?.trim().orEmpty()
            if (endpoint.isBlank() || healthCheckUrl.isBlank()) return null

            val defaults = HermesConfig(endpoint = endpoint, healthCheckUrl = healthCheckUrl)
            return HermesConfig(
                endpoint = endpoint,
                method = preferences.getString(KEY_METHOD, null)?.takeIf { it.isNotBlank() } ?: defaults.method,
                timeoutSeconds = preferences.getInt(KEY_TIMEOUT_SECONDS, defaults.timeoutSeconds),
                retryCount = preferences.getInt(KEY_RETRY_COUNT, defaults.retryCount),
                senderType =
                    SenderType.entries.firstOrNull {
                        it.name.equals(preferences.getString(KEY_SENDER_TYPE, null), ignoreCase = true)
                    } ?: SenderType.WEBHOOK,
                healthCheckUrl = healthCheckUrl,
            )
        }

        override fun save(config: HermesConfig) {
            val committed =
                preferences
                    .edit()
                    .putString(KEY_ENDPOINT, config.endpoint)
                    .putString(KEY_HEALTH_CHECK_URL, config.healthCheckUrl.orEmpty())
                    .putString(KEY_METHOD, config.method)
                    .putInt(KEY_TIMEOUT_SECONDS, config.timeoutSeconds)
                    .putInt(KEY_RETRY_COUNT, config.retryCount)
                    .putString(KEY_SENDER_TYPE, config.senderType.name)
                    .commit()
            check(committed) { "failed to persist the sync configuration" }
        }

        override fun clear() {
            check(preferences.edit().clear().commit()) {
                "failed to clear the sync configuration"
            }
        }

        companion object {
            const val PREFERENCES_NAME = "sync_config_v1"
            const val KEY_ENDPOINT = "endpoint"
            const val KEY_HEALTH_CHECK_URL = "health_check_url"
            const val KEY_METHOD = "method"
            const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
            const val KEY_RETRY_COUNT = "retry_count"
            const val KEY_SENDER_TYPE = "sender_type"
        }
    }
