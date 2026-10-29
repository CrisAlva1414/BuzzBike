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
import com.buzzbike.app.domain.sync.SyncWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

    private fun configuredViewModel(): SyncSettingsViewModel {
        val viewModel = viewModel()
        viewModel.onEndpointChange(SYNC_URL)
        viewModel.onHealthCheckUrlChange(HEALTH_URL)
        return viewModel
    }

    private fun viewModel(): SyncSettingsViewModel {
        store = InMemorySyncConfigStore()
        secretStorage = InMemorySecretStorage()
        secretManager =
            HmacSecretManager(
                sealer = IdentitySecretSealer(),
                storage = secretStorage,
            )
        scheduler = RecordingSyncWorkScheduler()
        outboxStore = RecordingOutboxStore()
        connectivity = FakeConnectivityChecker()
        return SyncSettingsViewModel(store, secretManager, scheduler, outboxStore, connectivity, dispatcher)
    }

    private class InMemorySyncConfigStore : SyncConfigStore {
        var config: HermesConfig? = null

        override fun current(): HermesConfig? = config

        override fun save(config: HermesConfig) {
            this.config = config
        }

        override fun clear() {
            config = null
        }
    }

    /** Reversible passthrough: enough for presence checks in these tests. */
    private class IdentitySecretSealer : SecretSealer {
        override fun seal(plaintext: ByteArray): ByteArray = plaintext.copyOf()

        override fun unseal(sealed: ByteArray): ByteArray = sealed.copyOf()
    }

    private class InMemorySecretStorage : SecretStorage {
        private var sealed: ByteArray? = null

        override fun readSealed(): ByteArray? = sealed?.copyOf()

        override fun writeSealed(sealed: ByteArray) {
            this.sealed = sealed.copyOf()
        }

        override fun clear() {
            sealed = null
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

        override fun schedule(config: HermesConfig) = Unit

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class RecordingOutboxStore : OutboxStore {
        var deleteAllCalls: Int = 0
            private set

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
        }
    }

    private companion object {
        const val SYNC_URL = "https://hermes.example/sync"
        const val HEALTH_URL = "https://hermes.example/health"
        const val VALID_SECRET = "0123456789abcdef0123456789abcdef"
        const val MAX_SECRET_BYTES = 4096
    }
}
