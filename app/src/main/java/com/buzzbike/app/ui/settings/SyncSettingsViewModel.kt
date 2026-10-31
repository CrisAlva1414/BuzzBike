package com.buzzbike.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.di.IoDispatcher
import com.buzzbike.app.domain.sync.ConnectivityChecker
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.ImportResult
import com.buzzbike.app.domain.sync.OutboxStore
import com.buzzbike.app.domain.sync.Reachability
import com.buzzbike.app.domain.sync.SyncConfigStore
import com.buzzbike.app.domain.sync.SyncMutationGate
import com.buzzbike.app.domain.sync.SyncWorkScheduler
import com.buzzbike.app.domain.sync.endpointValidationError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/** Outcome of the user-triggered "Probar conexión" action. */
enum class ConnectionStatus {
    IDLE,
    CHECKING,
    OK,
    UNREACHABLE,
    MISCONFIGURED,
}

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
     * Sticky flag set when a disconnect attempt left part of the sync state
     * behind (secret, config, outbox or queued work). While it is `true` the
     * screen must not render success messages and a connection probe must not
     * clear the revocation error.
     */
    val revocationFailed: Boolean = false,
    /**
     * `true` while a [SyncSettingsViewModel.save] or
     * [SyncSettingsViewModel.disconnect] is in flight. The screen disables the
     * mutating buttons so the UI cannot start a second, interleaved write.
     */
    val isBusy: Boolean = false,
    /**
     * Monotonic counter incremented on every successful save.
     *
     * The screen watches it to clear the write-only secret field exactly once
     * per save, instead of reacting to the sticky [isSaved] flag (which also
     * becomes true when a previous configuration is loaded).
     */
    val savedTick: Int = 0,
    /** Last result of [SyncSettingsViewModel.testConnection]; never carries a URL. */
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
)

/**
 * Settings-screen adapter over [SyncConfigStore] and [HmacSecretManager].
 *
 * Validation happens before persistence: an invalid URL or a failed secret
 * provisioning never overwrites the stored configuration. URLs and secrets are
 * never logged.
 *
 * All read-modify-write cycles over the sync configuration (load, save and
 * disconnect) are serialized through the shared [SyncMutationGate]. A revoke
 * also bumps its epoch; a save that started before the revoke aborts when it
 * reaches the gate instead of resurrecting the config and secret — even when
 * the disconnect came from another view-model instance.
 */
@HiltViewModel
class SyncSettingsViewModel
    @Inject
    constructor(
        operations: SyncSettingsOperations,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val store: SyncConfigStore = operations.store
        private val secretManager: HmacSecretManager = operations.secretManager
        private val scheduler: SyncWorkScheduler = operations.scheduler
        private val outboxStore: OutboxStore = operations.outboxStore
        private val connectivity: ConnectivityChecker = operations.connectivity
        private val gate: SyncMutationGate = operations.gate

        private val _uiState = MutableStateFlow(SyncSettingsUiState())
        val uiState: StateFlow<SyncSettingsUiState> = _uiState.asStateFlow()

        /** Serialized save body; see [SyncSaveRunner]. */
        private val saveRunner = SyncSaveRunner(store, secretManager, scheduler, gate)

        /**
         * Bumped by every user mutation (edit, save or disconnect). A [load]
         * or [save] result is applied only when this counter is unchanged, so
         * a slow IO result can never overwrite a later edit.
         */
        private val generation = AtomicInteger(0)

        /** Number of save/disconnect operations currently in flight. */
        private val mutationsInFlight = AtomicInteger(0)

        /** Makes [load] idempotent, but only after a successful read. */
        @Volatile
        private var loaded = false

        /**
         * Loads the persisted configuration and secret presence into the form.
         *
         * Runs at most once per view-model instance after a successful read:
         * rotation keeps the same instance, so recomposition must not reload
         * and discard unsaved edits. Both reads may touch disk and
         * [HmacSecretManager.hasSecret] can block on the Keystore lock, so they
         * run under [SyncMutationGate.withLock] on the IO dispatcher. A failed
         * read keeps [loaded] false and surfaces [ERROR_LOAD] so the caller can
         * retry.
         */
        fun load() {
            if (loaded) return
            val loadGeneration = generation.get()
            viewModelScope.launch {
                try {
                    val state =
                        withContext(ioDispatcher) {
                            gate.withLock {
                                syncSettingsState(
                                    config = store.current(),
                                    hasSecret = secretManager.hasSecret(),
                                )
                            }
                        }
                    loaded = true
                    if (generation.get() == loadGeneration) {
                        _uiState.value = state
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    _uiState.update { it.copy(error = ERROR_LOAD) }
                }
            }
        }

        /**
         * Editing any sync input invalidates the previous connection check:
         * a stale "Conexión verificada" would no longer describe the form, so
         * the status drops back to [ConnectionStatus.IDLE].
         */
        fun onEndpointChange(value: String) {
            generation.incrementAndGet()
            _uiState.update {
                it.copy(
                    endpoint = value,
                    isSaved = false,
                    error = null,
                    connectionStatus = ConnectionStatus.IDLE,
                )
            }
        }

        fun onHealthCheckUrlChange(value: String) {
            generation.incrementAndGet()
            _uiState.update {
                it.copy(
                    healthCheckUrl = value,
                    isSaved = false,
                    error = null,
                    connectionStatus = ConnectionStatus.IDLE,
                )
            }
        }

        /**
         * Updates the retry count from raw input, ignoring non-numeric values
         * so a half-typed field does not reset the form.
         */
        fun onRetryCountChange(value: String) {
            val retryCount = value.toIntOrNull() ?: return
            generation.incrementAndGet()
            _uiState.update {
                it.copy(
                    retryCount = retryCount,
                    isSaved = false,
                    error = null,
                    connectionStatus = ConnectionStatus.IDLE,
                )
            }
        }

        /**
         * Validates the form and persists it, importing [secret] when provided.
         *
         * The snapshot is taken at call time. Order matters: URL validation
         * runs first, then secret provisioning, and only then
         * [SyncConfigStore.save]. The configuration is never saved when either
         * validation or the secret fails.
         *
         * The whole sequence runs under [SyncMutationGate.withLock]. If
         * [disconnect] (from this or any other instance) revokes after this
         * call started, the operation aborts without persisting or scheduling
         * anything.
         */
        fun save(secret: String) {
            generation.incrementAndGet()
            val saveGeneration = generation.get()
            val startEpoch = gate.currentEpoch()
            val snapshot = _uiState.value
            mutationsInFlight.incrementAndGet()
            _uiState.update { it.copy(isBusy = true) }
            viewModelScope.launch {
                try {
                    val result =
                        withContext(ioDispatcher) {
                            gate.withLock { saveRunner.run(snapshot, secret, startEpoch) }
                        }
                    if (result != null &&
                        generation.get() == saveGeneration &&
                        !gate.isRevokedSince(startEpoch)
                    ) {
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
                } finally {
                    mutationsInFlight.decrementAndGet()
                    _uiState.update { it.copy(isBusy = mutationsInFlight.get() > 0) }
                }
            }
        }

        /**
         * Probes the configured health endpoint without sending any ride.
         *
         * URLs are validated with the same rules as [save] before any network
         * call, and a missing secret short-circuits to
         * [ConnectionStatus.MISCONFIGURED] without touching the checker. The
         * checker itself never throws, so the status is always one of
         * [ConnectionStatus.OK], [ConnectionStatus.UNREACHABLE] or
         * [ConnectionStatus.MISCONFIGURED].
         *
         * A probe never runs while a mutation is in flight or a previous
         * revocation failed, and its result is applied only if the revocation
         * epoch did not change. That keeps
         * [SyncSettingsUiState.revocationFailed] and `ERROR_DISCONNECT` from
         * being cleared by a stale probe.
         */
        fun testConnection() {
            if (_uiState.value.isBusy || _uiState.value.revocationFailed) return
            val startEpoch = gate.currentEpoch()
            val probeGeneration = generation.get()
            viewModelScope.launch {
                when (val outcome = evaluateConnection(startEpoch, probeGeneration)) {
                    is ConnectionOutcome.Error ->
                        if (isCurrentConnectionResult(startEpoch, probeGeneration)) {
                            _uiState.update {
                                it.copy(
                                    error = outcome.message,
                                    connectionStatus = ConnectionStatus.MISCONFIGURED,
                                )
                            }
                        }
                    is ConnectionOutcome.Done ->
                        if (isCurrentConnectionResult(startEpoch, probeGeneration)) {
                            _uiState.update {
                                it.copy(
                                    error = null,
                                    connectionStatus = outcome.status,
                                )
                            }
                        }
                }
            }
        }

        /**
         * `false` once the form changed, a disconnect revoked the epoch or a
         * revocation attempt failed; the caller must then discard the probe
         * result.
         */
        private fun isCurrentConnectionResult(
            startEpoch: Long,
            probeGeneration: Int,
        ): Boolean =
            generation.get() == probeGeneration &&
                !_uiState.value.revocationFailed &&
                !gate.isRevokedSince(startEpoch)

        /**
         * Runs the probe and reports either a user-facing error or the final
         * status. The single `return` keeps the control flow linear: validation
         * and secret checks short-circuit before any network call.
         */
        private suspend fun evaluateConnection(
            startEpoch: Long,
            probeGeneration: Int,
        ): ConnectionOutcome {
            val config = buildConfig(_uiState.value)
            val validationError = configValidationError(config)
            val hasSecret =
                validationError == null &&
                    withContext(ioDispatcher) { secretManager.hasSecret() }
            return when {
                validationError != null -> ConnectionOutcome.Error(validationError)
                !hasSecret -> ConnectionOutcome.Error(ERROR_SECRET_REQUIRED_FOR_TEST)
                else -> {
                    if (isCurrentConnectionResult(startEpoch, probeGeneration)) {
                        _uiState.update {
                            it.copy(connectionStatus = ConnectionStatus.CHECKING)
                        }
                    }
                    val reachability =
                        withContext(ioDispatcher) {
                            connectivity.check(config.healthCheckUrl.orEmpty(), true)
                        }
                    val status =
                        when (reachability) {
                            Reachability.Available -> ConnectionStatus.OK
                            Reachability.Unavailable -> ConnectionStatus.UNREACHABLE
                            Reachability.Misconfigured -> ConnectionStatus.MISCONFIGURED
                        }
                    ConnectionOutcome.Done(status)
                }
            }
        }

        /**
         * Revokes sync: clears the secret, the configuration, the outbox and
         * any queued work, in that order so a later failure can never leave a
         * usable secret behind.
         *
         * The shared gate is revoked synchronously before the coroutine starts,
         * so an older [save] waiting on the lock — in this instance or another
         * one — aborts instead of re-persisting what the user just revoked.
         * Cleanup runs inside `NonCancellable`, so navigating away or
         * cancelling the scope cannot interrupt revocation half-way.
         *
         * Every step is attempted even if an earlier one fails. The real
         * persisted state is re-read and surfaced in the UI; a partial failure
         * shows [ERROR_DISCONNECT] and sets [SyncSettingsUiState.revocationFailed]
         * instead of pretending sync is disconnected.
         */
        fun disconnect() {
            generation.incrementAndGet()
            gate.revoke()
            mutationsInFlight.incrementAndGet()
            _uiState.update { it.copy(isBusy = true) }
            viewModelScope.launch {
                try {
                    withContext(NonCancellable + ioDispatcher) {
                        gate.withLock {
                            var failed = clearSecret(secretManager)
                            val configFailed = clearConfig(store)
                            val outboxFailed = clearOutbox(outboxStore)
                            val cancelFailed = cancelWork(scheduler)
                            failed = failed || configFailed || outboxFailed || cancelFailed
                            _uiState.value =
                                revokedState(
                                    store = store,
                                    secretManager = secretManager,
                                    failed = failed,
                                ).copy(isBusy = true)
                        }
                    }
                } finally {
                    mutationsInFlight.decrementAndGet()
                    _uiState.update { it.copy(isBusy = mutationsInFlight.get() > 0) }
                }
            }
        }
    }

/**
 * Serialized body of [SyncSettingsViewModel.save].
 *
 * Validation, secret provisioning and persistence run here while the caller
 * holds the shared gate. Revocation is re-checked after secret
 * provisioning (the slow Keystore step), so a save that started before a
 * disconnect aborts instead of resurrecting the config and scheduling work.
 */
private class SyncSaveRunner(
    private val store: SyncConfigStore,
    private val secretManager: HmacSecretManager,
    private val scheduler: SyncWorkScheduler,
    private val gate: SyncMutationGate,
) {
    fun run(
        snapshot: SyncSettingsUiState,
        secret: String,
        startEpoch: Long,
    ): SaveResult? {
        if (isRevoked(startEpoch)) return null
        val config = buildConfig(snapshot)
        val validationError = configValidationError(config)
        return when {
            validationError != null -> SaveResult(config, validationError, secretManager.hasSecret())
            else -> commit(config, secret, startEpoch)
        }
    }

    private fun commit(
        config: HermesConfig,
        secret: String,
        startEpoch: Long,
    ): SaveResult? {
        val secretError = provisionSecret(secret)
        if (isRevoked(startEpoch)) return null
        val storageError = if (secretError == null) persist(config) else null
        if (secretError == null && storageError == null) scheduleAfterSave(scheduler, config)
        return SaveResult(config, secretError ?: storageError, secretManager.hasSecret())
    }

    private fun provisionSecret(secret: String): String? =
        if (secret.isBlank()) {
            if (secretManager.hasSecret()) null else ERROR_SECRET_REQUIRED
        } else {
            // A trailing newline from a copy/paste would silently change the
            // HMAC key; trim it before it reaches the sealer.
            val bytes = secret.trim().toByteArray(StandardCharsets.UTF_8)
            when (secretManager.importSecret(bytes)) {
                ImportResult.Success -> null
                ImportResult.TooShort -> ERROR_SECRET_TOO_SHORT
                ImportResult.TooLong -> ERROR_SECRET_TOO_LONG
                ImportResult.Failure -> ERROR_SECRET_STORAGE
            }
        }

    private fun persist(config: HermesConfig): String? =
        try {
            store.save(config)
            null
        } catch (expected: Exception) {
            ERROR_CONFIG_STORAGE
        }

    private fun isRevoked(startEpoch: Long): Boolean = gate.isRevokedSince(startEpoch)
}

/**
 * Refreshes both unique workers so they pick up the saved configuration
 * immediately instead of keeping the previous input data.
 *
 * A scheduling failure does not invalidate the save: the configuration is
 * already persisted and WorkManager is enqueued again on the next ride, so it
 * is intentionally not surfaced as a save error.
 */
private fun scheduleAfterSave(
    scheduler: SyncWorkScheduler,
    config: HermesConfig,
) {
    try {
        scheduler.schedule(config)
    } catch (expected: Exception) {
        // Retried on the next ride or periodic pass.
    }
}

/**
 * Builds a [HermesConfig] from a snapshot of the form.
 *
 * Kept as a top-level helper so the view-model stays under detekt's
 * function-count budget; it reads the passed [state], never the flow directly.
 */
private fun buildConfig(state: SyncSettingsUiState): HermesConfig =
    HermesConfig(
        endpoint = state.endpoint.trim(),
        retryCount = state.retryCount.coerceAtLeast(1),
        healthCheckUrl = state.healthCheckUrl.trim(),
    )

/** Same rules as [SyncSettingsViewModel.save], reusable without instance state. */
private fun configValidationError(config: HermesConfig): String? =
    endpointValidationError(config.endpoint) ?: config.healthCheckValidationError()

/** Maps persisted values into the form state, without carrying the secret. */
private fun syncSettingsState(
    config: HermesConfig?,
    hasSecret: Boolean,
    error: String? = null,
): SyncSettingsUiState =
    if (config == null) {
        SyncSettingsUiState(hasSecret = hasSecret, error = error)
    } else {
        SyncSettingsUiState(
            endpoint = config.endpoint,
            healthCheckUrl = config.healthCheckUrl.orEmpty(),
            retryCount = config.retryCount,
            hasSecret = hasSecret,
            isSaved = true,
            error = error,
        )
    }

/** Clears the sealed secret; returns `true` when the step failed. */
private fun clearSecret(secretManager: HmacSecretManager): Boolean =
    try {
        secretManager.clear()
        false
    } catch (expected: Exception) {
        true
    }

/** Clears the persisted config; returns `true` when the step failed. */
private fun clearConfig(store: SyncConfigStore): Boolean =
    try {
        store.clear()
        false
    } catch (expected: Exception) {
        true
    }

/** Drops queued payloads; returns `true` when the step failed. */
private suspend fun clearOutbox(outboxStore: OutboxStore): Boolean =
    try {
        outboxStore.deleteAll()
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: Exception) {
        true
    }

/** Cancels queued sync work; returns `true` when the step failed. */
private fun cancelWork(scheduler: SyncWorkScheduler): Boolean =
    try {
        scheduler.cancel()
        false
    } catch (expected: Exception) {
        true
    }

/**
 * Re-reads the persisted state after a revoke attempt.
 *
 * The UI must reflect what is actually on disk, not what the revoke intended:
 * if the secret survived, [SyncSettingsUiState.hasSecret] stays `true`; if the
 * config survived, its values are shown so the user can retry. A failed step
 * keeps [ERROR_DISCONNECT] visible instead of claiming success.
 */
private fun revokedState(
    store: SyncConfigStore,
    secretManager: HmacSecretManager,
    failed: Boolean,
): SyncSettingsUiState {
    val config =
        try {
            store.current()
        } catch (expected: Exception) {
            null
        }
    val hasSecret =
        try {
            secretManager.hasSecret()
        } catch (expected: Exception) {
            true
        }
    return syncSettingsState(
        config = config,
        hasSecret = hasSecret,
        error = if (failed) ERROR_DISCONNECT else null,
    ).copy(revocationFailed = failed)
}

/** Result of probing the configured endpoint without sending any ride. */
private sealed interface ConnectionOutcome {
    data class Error(
        val message: String,
    ) : ConnectionOutcome

    data class Done(
        val status: ConnectionStatus,
    ) : ConnectionOutcome
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
private const val ERROR_SECRET_REQUIRED_FOR_TEST = "guardá el secreto primero"
private const val ERROR_CONFIG_STORAGE = "could not save the sync configuration"
private const val ERROR_DISCONNECT = "no se pudo revocar el sync; reintentá"
private const val ERROR_LOAD = "no se pudo cargar la configuración; reintentá"
