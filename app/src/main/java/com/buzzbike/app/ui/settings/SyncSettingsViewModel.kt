package com.buzzbike.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.di.IoDispatcher
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.ImportResult
import com.buzzbike.app.domain.sync.OutboxStore
import com.buzzbike.app.domain.sync.SyncConfigStore
import com.buzzbike.app.domain.sync.SyncWorkScheduler
import com.buzzbike.app.domain.sync.endpointValidationError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * UI state for the sync settings form.
 *
 * The HMAC secret is intentionally absent: it is write-only from the UI's
 * perspective and never round-trips through state (AGENTS.md §7).
 */
data class SyncSettingsUiState(
    val endpoint: String = "",
    val healthCheckUrl: String = "",
    val retryCount: Int = DEFAULT_RETRY_COUNT,
    val hasSecret: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null,
    /**
     * Monotonic counter incremented on every successful save.
     *
     * The screen watches it to clear the write-only secret field exactly once
     * per save, instead of reacting to the sticky [isSaved] flag (which also
     * becomes true when a previous configuration is loaded).
     */
    val savedTick: Int = 0,
)

/**
 * Settings-screen adapter over [SyncConfigStore] and [HmacSecretManager].
 *
 * Validation happens before persistence: an invalid URL or a failed secret
 * provisioning never overwrites the stored configuration. URLs and secrets are
 * never logged.
 */
@HiltViewModel
class SyncSettingsViewModel
    @Inject
    constructor(
        private val store: SyncConfigStore,
        private val secretManager: HmacSecretManager,
        private val scheduler: SyncWorkScheduler,
        private val outboxStore: OutboxStore,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SyncSettingsUiState())
        val uiState: StateFlow<SyncSettingsUiState> = _uiState.asStateFlow()

        /** Loads the persisted configuration and secret presence into the form. */
        fun load() {
            val config = store.current()
            val hasSecret = secretManager.hasSecret()
            _uiState.value =
                if (config == null) {
                    SyncSettingsUiState(hasSecret = hasSecret)
                } else {
                    SyncSettingsUiState(
                        endpoint = config.endpoint,
                        healthCheckUrl = config.healthCheckUrl.orEmpty(),
                        retryCount = config.retryCount,
                        hasSecret = hasSecret,
                        isSaved = true,
                    )
                }
        }

        fun onEndpointChange(value: String) {
            _uiState.update { it.copy(endpoint = value, isSaved = false, error = null) }
        }

        fun onHealthCheckUrlChange(value: String) {
            _uiState.update { it.copy(healthCheckUrl = value, isSaved = false, error = null) }
        }

        /**
         * Updates the retry count from raw input, ignoring non-numeric values
         * so a half-typed field does not reset the form.
         */
        fun onRetryCountChange(value: String) {
            val retryCount = value.toIntOrNull() ?: return
            _uiState.update { it.copy(retryCount = retryCount, isSaved = false, error = null) }
        }

        /**
         * Validates the form and persists it, importing [secret] when provided.
         *
         * Order matters: URL validation runs first, then secret provisioning,
         * and only then [SyncConfigStore.save]. The configuration is never
         * saved when either validation or the secret fails.
         */
        fun save(secret: String) {
            viewModelScope.launch {
                val config = buildConfig()
                val result =
                    withContext(ioDispatcher) {
                        val error =
                            configValidationError(config)
                                ?: secretProvisioningError(secret)
                                ?: persistError(config)
                        SaveResult(config, error, secretManager.hasSecret())
                    }
                _uiState.update { state ->
                    if (result.error == null) {
                        state.copy(
                            endpoint = result.config.endpoint,
                            healthCheckUrl = result.config.healthCheckUrl.orEmpty(),
                            retryCount = result.config.retryCount,
                            hasSecret = result.hasSecret,
                            isSaved = true,
                            error = null,
                            savedTick = state.savedTick + 1,
                        )
                    } else {
                        state.copy(isSaved = false, error = result.error)
                    }
                }
            }
        }

        /**
         * Revokes sync: cancels queued work, drops every queued payload, and
         * clears both the configuration and the sealed secret.
         *
         * WorkManager, Room and the Keystore are all blocking, so the whole
         * revocation runs off the main thread; the state reset happens once the
         * destructive work has finished.
         */
        fun disconnect() {
            viewModelScope.launch {
                withContext(ioDispatcher) {
                    scheduler.cancel()
                    outboxStore.deleteAll()
                    store.clear()
                    secretManager.clear()
                }
                _uiState.value = SyncSettingsUiState()
            }
        }

        private fun buildConfig(): HermesConfig {
            val state = _uiState.value
            return HermesConfig(
                endpoint = state.endpoint.trim(),
                retryCount = state.retryCount.coerceAtLeast(1),
                healthCheckUrl = state.healthCheckUrl.trim(),
            )
        }

        private fun configValidationError(config: HermesConfig): String? =
            endpointValidationError(config.endpoint) ?: config.healthCheckValidationError()

        private fun secretProvisioningError(secret: String): String? =
            if (secret.isBlank()) {
                if (secretManager.hasSecret()) null else ERROR_SECRET_REQUIRED
            } else {
                // A trailing newline from a copy/paste would silently change
                // the HMAC key; trim it before it reaches the sealer.
                val bytes = secret.trim().toByteArray(StandardCharsets.UTF_8)
                when (secretManager.importSecret(bytes)) {
                    ImportResult.Success -> null
                    ImportResult.TooShort -> ERROR_SECRET_TOO_SHORT
                    ImportResult.TooLong -> ERROR_SECRET_TOO_LONG
                    ImportResult.Failure -> ERROR_SECRET_STORAGE
                }
            }

        private fun persistError(config: HermesConfig): String? =
            try {
                store.save(config)
                null
            } catch (expected: Exception) {
                ERROR_CONFIG_STORAGE
            }
    }

/** Outcome of the off-main-thread portion of [SyncSettingsViewModel.save]. */
private data class SaveResult(
    val config: HermesConfig,
    val error: String?,
    val hasSecret: Boolean,
)

private const val DEFAULT_RETRY_COUNT = 3
private const val ERROR_SECRET_REQUIRED = "secret is required"
private const val ERROR_SECRET_TOO_SHORT = "secret must be at least 32 bytes"
private const val ERROR_SECRET_TOO_LONG = "secret must be at most 4096 bytes"
private const val ERROR_SECRET_STORAGE = "could not store the secret"
private const val ERROR_CONFIG_STORAGE = "could not save the sync configuration"
