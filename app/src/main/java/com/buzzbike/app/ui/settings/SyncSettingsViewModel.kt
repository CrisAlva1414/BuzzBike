package com.buzzbike.app.ui.settings

import androidx.lifecycle.ViewModel
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.ImportResult
import com.buzzbike.app.domain.sync.SyncConfigStore
import com.buzzbike.app.domain.sync.endpointValidationError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
            val config = buildConfig()
            val error =
                configValidationError(config)
                    ?: secretProvisioningError(secret)
                    ?: persistError(config)
            _uiState.update { state ->
                if (error == null) {
                    state.copy(
                        endpoint = config.endpoint,
                        healthCheckUrl = config.healthCheckUrl.orEmpty(),
                        retryCount = config.retryCount,
                        hasSecret = secretManager.hasSecret(),
                        isSaved = true,
                        error = null,
                    )
                } else {
                    state.copy(isSaved = false, error = error)
                }
            }
        }

        /** Removes both the stored configuration and the sealed secret. */
        fun disconnect() {
            store.clear()
            secretManager.clear()
            _uiState.value = SyncSettingsUiState()
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
                when (secretManager.importSecret(secret.toByteArray(StandardCharsets.UTF_8))) {
                    ImportResult.Success -> null
                    ImportResult.TooShort -> ERROR_SECRET_TOO_SHORT
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

private const val DEFAULT_RETRY_COUNT = 3
private const val ERROR_SECRET_REQUIRED = "secret is required"
private const val ERROR_SECRET_TOO_SHORT = "secret must be at least 32 bytes"
private const val ERROR_SECRET_STORAGE = "could not store the secret"
private const val ERROR_CONFIG_STORAGE = "could not save the sync configuration"
