package com.buzzbike.app.ui.settings

import com.buzzbike.app.domain.sync.ConnectivityChecker
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.OutboxItem
import com.buzzbike.app.domain.sync.OutboxStore
import com.buzzbike.app.domain.sync.Reachability
import com.buzzbike.app.domain.sync.SecretSealer
import com.buzzbike.app.domain.sync.SecretStorage
import com.buzzbike.app.domain.sync.SyncConfigStore
import com.buzzbike.app.domain.sync.SyncMutationGate
import com.buzzbike.app.domain.sync.SyncWorkScheduler
import com.buzzbike.app.domain.sync.UnsealedSecret
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM tests for [SyncSettingsViewModel].
 *
 * The store and the secret backend are in-memory fakes, so assertions inspect
 * exactly what would reach disk. The secret is deliberately absent from every
 * expected state: it is write-only from the UI's perspective.
 *
 * Save and disconnect hop to an injected IO dispatcher, so every test drives
 * the view-model coroutines with a [StandardTestDispatcher] installed as
 * `Dispatchers.Main` and then calls `advanceUntilIdle()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: InMemorySyncConfigStore
    private lateinit var secretManager: HmacSecretManager
    private lateinit var secretStorage: InMemorySecretStorage
    private lateinit var scheduler: RecordingSyncWorkScheduler
    private lateinit var outboxStore: RecordingOutboxStore
    private lateinit var connectivity: FakeConnectivityChecker

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun given_httpEndpoint_when_save_then_errorAndNothingSaved() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange("http://hermes.example/sync")
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save(VALID_SECRET)
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.error)
            assertNull(store.config)
        }

    @Test
    fun given_endpointWithQuery_when_save_then_errorAndNothingSaved() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange("https://hermes.example/sync?token=abc")
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save(VALID_SECRET)
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.error)
            assertNull(store.config)
        }

    @Test
    fun given_noSecretAndNoStoredSecret_when_save_then_secretRequired() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save("")
            advanceUntilIdle()

            assertEquals("secret is required", viewModel.uiState.value.error)
            assertNull(store.config)
        }

    @Test
    fun given_shortSecret_when_save_then_errorAndNothingSaved() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)
            val shortSecret = "too-short"

            viewModel.save(shortSecret)
            advanceUntilIdle()

            assertEquals("secret must be at least 32 bytes", viewModel.uiState.value.error)
            assertNull(store.config)
        }

    @Test
    fun given_oversizedSecret_when_save_then_tooLongErrorAndNothingSaved() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)
            val oversizedSecret = "a".repeat(MAX_SECRET_BYTES + 1)

            viewModel.save(oversizedSecret)
            advanceUntilIdle()

            assertEquals("secret must be at most 4096 bytes", viewModel.uiState.value.error)
            assertNull(store.config)
            assertFalse(secretManager.hasSecret())
        }

    @Test
    fun given_validFormAndSecret_when_save_then_schedulesWorkersWithSavedConfig() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save(VALID_SECRET)
            advanceUntilIdle()

            val expected = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)
            assertEquals(expected, store.config)
            assertEquals(listOf(expected), scheduler.scheduledConfigs)
        }

    @Test
    fun given_rejectedForm_when_save_then_workersAreNotRescheduled() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange("http://hermes.example/sync")
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save(VALID_SECRET)
            advanceUntilIdle()

            assertTrue(scheduler.scheduledConfigs.isEmpty())
        }

    @Test
    fun given_okConnection_when_endpointEdited_then_connectionStatusIsIdle() =
        runTest {
            val viewModel = configuredViewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            connectivity.reachability = Reachability.Available
            viewModel.testConnection()
            advanceUntilIdle()
            assertEquals(ConnectionStatus.OK, viewModel.uiState.value.connectionStatus)

            viewModel.onEndpointChange("https://other.example/sync")

            assertEquals(ConnectionStatus.IDLE, viewModel.uiState.value.connectionStatus)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun given_okConnection_when_healthCheckUrlEdited_then_connectionStatusIsIdle() =
        runTest {
            val viewModel = configuredViewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            connectivity.reachability = Reachability.Available
            viewModel.testConnection()
            advanceUntilIdle()
            assertEquals(ConnectionStatus.OK, viewModel.uiState.value.connectionStatus)

            viewModel.onHealthCheckUrlChange("https://other.example/health")

            assertEquals(ConnectionStatus.IDLE, viewModel.uiState.value.connectionStatus)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun given_okConnection_when_retryCountEdited_then_connectionStatusIsIdle() =
        runTest {
            val viewModel = configuredViewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            connectivity.reachability = Reachability.Available
            viewModel.testConnection()
            advanceUntilIdle()
            assertEquals(ConnectionStatus.OK, viewModel.uiState.value.connectionStatus)

            viewModel.onRetryCountChange("5")

            assertEquals(ConnectionStatus.IDLE, viewModel.uiState.value.connectionStatus)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun given_validFormAndSecret_when_save_then_storeHasConfigAndSecretIsPresent() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save(VALID_SECRET)
            advanceUntilIdle()

            assertEquals(
                HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL),
                store.config,
            )
            assertTrue(viewModel.uiState.value.hasSecret)
            assertTrue(viewModel.uiState.value.isSaved)
            assertNull(viewModel.uiState.value.error)
            assertEquals(1, viewModel.uiState.value.savedTick)
        }

    @Test
    fun given_secretWithTrailingNewline_when_save_then_keyIsTrimmed() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save("$VALID_SECRET\n")
            advanceUntilIdle()

            val stored = secretStorage.readSealed()
            assertNotNull(stored)
            assertArrayEquals(VALID_SECRET.toByteArray(StandardCharsets.UTF_8), stored)
            assertTrue(viewModel.uiState.value.isSaved)
        }

    @Test
    fun given_validFormAndPreviousSecret_when_saveWithoutSecret_then_configIsSaved() =
        runTest {
            val viewModel = viewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save("")
            advanceUntilIdle()

            assertNotNull(store.config)
            assertTrue(viewModel.uiState.value.hasSecret)
            assertTrue(viewModel.uiState.value.isSaved)
        }

    @Test
    fun given_disconnectedSync_when_saveStartsAfter_then_persistsAndSchedules() =
        runTest {
            val viewModel = viewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)

            viewModel.disconnect()
            advanceUntilIdle()
            assertNull(store.config)

            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)
            viewModel.save(VALID_SECRET)
            advanceUntilIdle()

            val expected = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)
            assertEquals(expected, store.config)
            assertEquals(listOf(expected), scheduler.scheduledConfigs)
            assertFalse(viewModel.uiState.value.isBusy)
        }

    @Test
    fun given_saveInFlightBeforeDisconnect_when_disconnect_then_abortsWithoutPersistingOrScheduling() =
        runBlocking {
            Dispatchers.setMain(Dispatchers.Unconfined)
            val sealer = GatedSecretSealer()
            val viewModel = viewModel(ioDispatcher = Dispatchers.IO, sealer = sealer)
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save(VALID_SECRET)
            assertTrue("save never reached the secret sealer", sealer.awaitEntered())

            viewModel.disconnect()
            sealer.releaseGate()
            awaitNotBusy(viewModel)

            assertNull("a save revoked mid-flight must not persist the config", store.config)
            assertFalse("the secret must stay revoked", secretManager.hasSecret())
            assertTrue("a revoked save must not schedule work", scheduler.scheduledConfigs.isEmpty())
        }

    @Test
    fun given_editDuringSlowSave_when_saveCompletes_then_editIsNotOverwritten() =
        runBlocking {
            Dispatchers.setMain(Dispatchers.Unconfined)
            val sealer = GatedSecretSealer()
            val viewModel = viewModel(ioDispatcher = Dispatchers.IO, sealer = sealer)
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save(VALID_SECRET)
            assertTrue("save never reached the secret sealer", sealer.awaitEntered())

            viewModel.onEndpointChange(EDITED_SYNC_URL)
            sealer.releaseGate()
            awaitNotBusy(viewModel)

            assertEquals(EDITED_SYNC_URL, viewModel.uiState.value.endpoint)
            assertEquals(
                "the save persisted the snapshot it started with",
                SYNC_URL,
                store.config?.endpoint,
            )
        }

    @Test
    fun given_storedConfig_when_load_then_populatesFormAndSecondLoadKeepsEdits() =
        runTest {
            val viewModel = viewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL, retryCount = 5)

            viewModel.load()
            advanceUntilIdle()

            assertEquals(SYNC_URL, viewModel.uiState.value.endpoint)
            assertEquals(HEALTH_URL, viewModel.uiState.value.healthCheckUrl)
            assertEquals(5, viewModel.uiState.value.retryCount)
            assertTrue(viewModel.uiState.value.hasSecret)

            viewModel.onEndpointChange(EDITED_SYNC_URL)
            viewModel.load()
            advanceUntilIdle()

            assertEquals(EDITED_SYNC_URL, viewModel.uiState.value.endpoint)
        }

    @Test
    fun given_pendingLoad_when_editedBeforeItCompletes_then_editIsNotOverwritten() =
        runTest {
            val viewModel = viewModel()
            store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)

            viewModel.load()
            viewModel.onEndpointChange(EDITED_SYNC_URL)
            viewModel.onHealthCheckUrlChange(EDITED_HEALTH_URL)
            advanceUntilIdle()

            assertEquals(EDITED_SYNC_URL, viewModel.uiState.value.endpoint)
            assertEquals(EDITED_HEALTH_URL, viewModel.uiState.value.healthCheckUrl)
        }

    @Test
    fun given_loadThrows_when_load_then_errorAndRetrySucceeds() =
        runTest {
            val viewModel = viewModel()
            store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)
            store.currentFailures = 1

            viewModel.load()
            advanceUntilIdle()

            assertNotNull("a failed read must surface an error", viewModel.uiState.value.error)

            viewModel.load()
            advanceUntilIdle()

            assertEquals(SYNC_URL, viewModel.uiState.value.endpoint)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun given_savedConfigAndSecret_when_disconnect_then_bothAreCleared() =
        runTest {
            val viewModel = viewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)

            viewModel.disconnect()
            advanceUntilIdle()

            assertNull(store.config)
            assertFalse(secretManager.hasSecret())
            assertEquals(SyncSettingsUiState(), viewModel.uiState.value)
        }

    @Test
    fun given_queuedWorkAndOutbox_when_disconnect_then_revokesEverything() =
        runTest {
            val viewModel = viewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)

            viewModel.disconnect()
            advanceUntilIdle()

            assertEquals(1, scheduler.cancelCalls)
            assertEquals(1, outboxStore.deleteAllCalls)
            assertNull(store.config)
            assertFalse(secretManager.hasSecret())
        }

    @Test
    fun given_outboxCleanupFails_when_disconnect_then_secretIsStillClearedAndErrorIsReported() =
        runTest {
            val viewModel = viewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)
            outboxStore.failDeleteAll = true

            viewModel.disconnect()
            advanceUntilIdle()

            assertFalse("a failed outbox cleanup must not keep the secret", secretManager.hasSecret())
            assertNull("config cleanup still runs after the outbox failure", store.config)
            assertNotNull(viewModel.uiState.value.error)
            assertEquals("", viewModel.uiState.value.endpoint)
            assertFalse(viewModel.uiState.value.hasSecret)
        }

    @Test
    fun given_secretClearFails_when_disconnect_then_errorAndSecretStillPresent() =
        runTest {
            val viewModel = viewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)
            secretStorage.failClear = true

            viewModel.disconnect()
            advanceUntilIdle()

            assertEquals("no se pudo revocar el sync; reintentá", viewModel.uiState.value.error)
            assertTrue(
                "the UI must not claim disconnect without clearing the secret",
                viewModel.uiState.value.hasSecret,
            )
            assertFalse(viewModel.uiState.value.isBusy)
        }

    @Test
    fun given_configClearFails_when_disconnect_then_secretGoneAndErrorVisible() =
        runTest {
            val viewModel = viewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)
            store.failClear = true

            viewModel.disconnect()
            advanceUntilIdle()

            assertFalse(secretManager.hasSecret())
            assertFalse("sync is inactive without a secret", viewModel.uiState.value.hasSecret)
            assertNotNull(viewModel.uiState.value.error)
            assertNotNull("the surviving config is shown for a retry", store.config)
            assertEquals(SYNC_URL, viewModel.uiState.value.endpoint)

            store.failClear = false
            viewModel.disconnect()
            advanceUntilIdle()

            assertNull(store.config)
            assertNull(viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.hasSecret)
        }

    @Test
    fun given_availableBackendAndSecret_when_testConnection_then_ok() =
        runTest {
            val viewModel = configuredViewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            connectivity.reachability = Reachability.Available

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(ConnectionStatus.OK, viewModel.uiState.value.connectionStatus)
            assertEquals(HEALTH_URL, connectivity.lastUrl)
            assertEquals(1, connectivity.calls)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun given_unavailableBackend_when_testConnection_then_unreachable() =
        runTest {
            val viewModel = configuredViewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            connectivity.reachability = Reachability.Unavailable

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(ConnectionStatus.UNREACHABLE, viewModel.uiState.value.connectionStatus)
            assertEquals(1, connectivity.calls)
        }

    @Test
    fun given_misconfiguredBackend_when_testConnection_then_misconfigured() =
        runTest {
            val viewModel = configuredViewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            connectivity.reachability = Reachability.Misconfigured

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(ConnectionStatus.MISCONFIGURED, viewModel.uiState.value.connectionStatus)
            assertEquals(1, connectivity.calls)
        }

    @Test
    fun given_noSecret_when_testConnection_then_misconfiguredWithoutCallingChecker() =
        runTest {
            val viewModel = configuredViewModel()

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(ConnectionStatus.MISCONFIGURED, viewModel.uiState.value.connectionStatus)
            assertEquals("guardá el secreto primero", viewModel.uiState.value.error)
            assertEquals(0, connectivity.calls)
        }

    @Test
    fun given_invalidUrl_when_testConnection_then_misconfiguredWithoutCallingChecker() =
        runTest {
            val viewModel = viewModel()
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            viewModel.onEndpointChange("http://hermes.example/sync")
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(ConnectionStatus.MISCONFIGURED, viewModel.uiState.value.connectionStatus)
            assertNotNull(viewModel.uiState.value.error)
            assertEquals(0, connectivity.calls)
        }

    @Test
    fun given_secret_when_save_then_stateNeverContainsTheSecret() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            viewModel.save(VALID_SECRET)
            advanceUntilIdle()

            val renderedState = viewModel.uiState.value.toString()
            assertFalse(renderedState.contains(VALID_SECRET))
        }

    @Test
    fun given_twoViewModelsSharingGate_when_disconnectDuringSave_then_saveAbortsAndNothingIsScheduled() =
        runBlocking {
            Dispatchers.setMain(Dispatchers.Unconfined)
            val sealer = GatedSecretSealer()
            val operations = buildOperations(gate = SyncMutationGate(), sealer = sealer)
            val first = SyncSettingsViewModel(operations, Dispatchers.IO)
            val second = SyncSettingsViewModel(operations, Dispatchers.IO)

            first.onEndpointChange(SYNC_URL)
            first.onHealthCheckUrlChange(HEALTH_URL)

            first.save(VALID_SECRET)
            assertTrue("save never reached the secret sealer", sealer.awaitEntered())

            second.disconnect()
            assertTrue(
                "disconnect must be in flight while the save owns the shared gate",
                second.uiState.value.isBusy,
            )

            sealer.releaseGate()
            awaitNotBusy(first)
            awaitNotBusy(second)

            assertNull(
                "a save revoked by another instance must not persist the config",
                store.config,
            )
            assertFalse("the secret must stay revoked", secretManager.hasSecret())
            assertTrue("a revoked save must not schedule work", scheduler.scheduledConfigs.isEmpty())
        }

    @Test
    fun given_probeInFlight_when_formEdited_then_staleResultIsDiscardedAndCurrentErrorIsKept() =
        runTest {
            val suspendingConnectivity = SuspendingConnectivityChecker()
            val viewModel = viewModel(connectivityOverride = suspendingConnectivity)
            viewModel.onEndpointChange(SYNC_URL)
            viewModel.onHealthCheckUrlChange(HEALTH_URL)

            // Seed an error that did not come from the probe.
            viewModel.save("too-short")
            advanceUntilIdle()
            assertEquals("secret must be at least 32 bytes", viewModel.uiState.value.error)

            // A stored secret lets the next probe reach the network.
            secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
            viewModel.testConnection()
            advanceUntilIdle()

            assertTrue("probe never reached the connectivity checker", suspendingConnectivity.entered.isCompleted)
            assertEquals(
                "starting a probe must not clear an unrelated error",
                "secret must be at least 32 bytes",
                viewModel.uiState.value.error,
            )
            assertEquals(ConnectionStatus.CHECKING, viewModel.uiState.value.connectionStatus)

            // The form changes while probe A is still in flight, and a
            // different operation publishes its own error in the meantime.
            viewModel.onEndpointChange(EDITED_SYNC_URL)
            viewModel.save("too-short")
            advanceUntilIdle()
            val errorBeforeRelease = viewModel.uiState.value.error
            assertNotNull(errorBeforeRelease)

            suspendingConnectivity.release.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                "a stale probe must not overwrite the current error",
                errorBeforeRelease,
                viewModel.uiState.value.error,
            )
            assertEquals(ConnectionStatus.IDLE, viewModel.uiState.value.connectionStatus)
        }

    private fun configuredViewModel(): SyncSettingsViewModel {
        val viewModel = viewModel()
        viewModel.onEndpointChange(SYNC_URL)
        viewModel.onHealthCheckUrlChange(HEALTH_URL)
        return viewModel
    }

    private fun viewModel(
        ioDispatcher: CoroutineDispatcher = dispatcher,
        sealer: SecretSealer = IdentitySecretSealer(),
        connectivityOverride: ConnectivityChecker? = null,
    ): SyncSettingsViewModel =
        SyncSettingsViewModel(
            operations = buildOperations(sealer = sealer, connectivityOverride = connectivityOverride),
            ioDispatcher = ioDispatcher,
        )

    /**
     * Builds the shared sync ports for one or more view-model instances.
     *
     * Passing the same [gate] (and reusing the returned operations) is what
     * makes two instances share the mutation lock and revocation epoch.
     */
    private fun buildOperations(
        gate: SyncMutationGate = SyncMutationGate(),
        sealer: SecretSealer = IdentitySecretSealer(),
        connectivityOverride: ConnectivityChecker? = null,
    ): SyncSettingsOperations {
        store = InMemorySyncConfigStore()
        secretStorage = InMemorySecretStorage()
        secretManager =
            HmacSecretManager(
                sealer = sealer,
                storage = secretStorage,
            )
        scheduler = RecordingSyncWorkScheduler()
        outboxStore = RecordingOutboxStore()
        connectivity = FakeConnectivityChecker()
        return SyncSettingsOperations(
            store = store,
            secretManager = secretManager,
            scheduler = scheduler,
            outboxStore = outboxStore,
            connectivity = connectivityOverride ?: connectivity,
            gate = gate,
        )
    }

    private suspend fun awaitNotBusy(viewModel: SyncSettingsViewModel) {
        withTimeout(AWAIT_TIMEOUT_MILLIS) {
            while (viewModel.uiState.value.isBusy) {
                delay(AWAIT_POLL_MILLIS)
            }
        }
    }

    private class InMemorySyncConfigStore : SyncConfigStore {
        var config: HermesConfig? = null

        /** Simulates a failing [clear], leaving the config behind. */
        var failClear: Boolean = false

        /** Number of [current] calls that must throw before recovering. */
        var currentFailures: Int = 0

        override fun current(): HermesConfig? {
            if (currentFailures > 0) {
                currentFailures -= 1
                throw IllegalStateException("config store unavailable")
            }
            return config
        }

        override fun save(config: HermesConfig) {
            this.config = config
        }

        override fun clear() {
            if (failClear) throw IllegalStateException("config clear failed")
            config = null
        }
    }

    /** Reversible passthrough: enough for presence checks in these tests. */
    private class IdentitySecretSealer : SecretSealer {
        override fun seal(
            plaintext: ByteArray,
            purpose: String,
        ): ByteArray = plaintext.copyOf()

        override fun unseal(
            sealed: ByteArray,
            purpose: String,
        ): UnsealedSecret = UnsealedSecret(sealed.copyOf(), wasLegacy = false)
    }

    private class InMemorySecretStorage : SecretStorage {
        private var sealed: ByteArray? = null

        /** Simulates a failing [clear], leaving the sealed secret behind. */
        var failClear: Boolean = false

        override fun readSealed(): ByteArray? = sealed?.copyOf()

        override fun writeSealed(sealed: ByteArray) {
            this.sealed = sealed.copyOf()
        }

        override fun clear() {
            if (failClear) throw IllegalStateException("secret clear failed")
            sealed = null
        }
    }

    /**
     * Identity sealer that blocks inside [seal] until the test opens the gate.
     *
     * Lets a test hold a save inside its secret-provisioning step (while it
     * owns the view-model mutex) and start a disconnect in the meantime.
     */
    private class GatedSecretSealer : SecretSealer {
        private val entered = CountDownLatch(1)
        private val gate = CountDownLatch(1)

        fun awaitEntered(): Boolean = entered.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        fun releaseGate() {
            gate.countDown()
        }

        override fun seal(
            plaintext: ByteArray,
            purpose: String,
        ): ByteArray {
            entered.countDown()
            check(gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "seal gate was never released" }
            return plaintext.copyOf()
        }

        override fun unseal(
            sealed: ByteArray,
            purpose: String,
        ): UnsealedSecret = UnsealedSecret(sealed.copyOf(), wasLegacy = false)
    }

    /**
     * Connectivity checker that suspends inside [check] until the test
     * releases it, so a probe can be held in flight deterministically.
     */
    private class SuspendingConnectivityChecker(
        var reachability: Reachability = Reachability.Available,
    ) : ConnectivityChecker {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun check(
            healthCheckUrl: String,
            hmacKeyConfigured: Boolean,
        ): Reachability {
            entered.complete(Unit)
            release.await()
            return reachability
        }
    }

    private class FakeConnectivityChecker(
        var reachability: Reachability = Reachability.Available,
    ) : ConnectivityChecker {
        var calls: Int = 0
            private set
        var lastUrl: String? = null
            private set

        override suspend fun check(
            healthCheckUrl: String,
            hmacKeyConfigured: Boolean,
        ): Reachability {
            calls += 1
            lastUrl = healthCheckUrl
            return reachability
        }
    }

    private class RecordingSyncWorkScheduler : SyncWorkScheduler {
        var cancelCalls: Int = 0
            private set
        val scheduledConfigs: MutableList<HermesConfig> = mutableListOf()

        override fun schedule(config: HermesConfig) {
            scheduledConfigs += config
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class RecordingOutboxStore : OutboxStore {
        var deleteAllCalls: Int = 0
            private set

        /** Simulates a Room failure during [deleteAll]. */
        var failDeleteAll: Boolean = false

        override suspend fun getPending(
            nowMillis: Long,
            limit: Int,
        ): List<OutboxItem> = emptyList()

        override suspend fun markSent(
            id: String,
            nowMillis: Long,
        ) = Unit

        override suspend fun markFailed(
            id: String,
            error: String,
            nowMillis: Long,
            nextAttemptAtMillis: Long,
        ) = Unit

        override suspend fun deleteSent() = Unit

        override suspend fun deleteFailedOlderThan(cutoffMillis: Long) = Unit

        override suspend fun deleteAll() {
            deleteAllCalls += 1
            if (failDeleteAll) throw IllegalStateException("outbox unavailable")
        }
    }

    private companion object {
        const val SYNC_URL = "https://hermes.example/sync"
        const val HEALTH_URL = "https://hermes.example/health"
        const val EDITED_SYNC_URL = "https://edited.example/sync"
        const val EDITED_HEALTH_URL = "https://edited.example/health"
        const val VALID_SECRET = "0123456789abcdef0123456789abcdef"
        const val MAX_SECRET_BYTES = 4096
        const val AWAIT_TIMEOUT_MILLIS = 5_000L
        const val AWAIT_POLL_MILLIS = 10L
        const val GATE_TIMEOUT_SECONDS = 5L
    }
}
