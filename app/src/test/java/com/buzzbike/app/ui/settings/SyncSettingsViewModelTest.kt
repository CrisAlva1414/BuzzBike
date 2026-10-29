package com.buzzbike.app.ui.settings

import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.SecretSealer
import com.buzzbike.app.domain.sync.SecretStorage
import com.buzzbike.app.domain.sync.SyncConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * JVM tests for [SyncSettingsViewModel].
 *
 * The store and the secret backend are in-memory fakes, so assertions inspect
 * exactly what would reach disk. The secret is deliberately absent from every
 * expected state: it is write-only from the UI's perspective.
 */
class SyncSettingsViewModelTest {
    private lateinit var store: InMemorySyncConfigStore
    private lateinit var secretManager: HmacSecretManager

    @Test
    fun given_httpEndpoint_when_save_then_errorAndNothingSaved() {
        val viewModel = viewModel()
        viewModel.onEndpointChange("http://hermes.example/sync")
        viewModel.onHealthCheckUrlChange(HEALTH_URL)

        viewModel.save(VALID_SECRET)

        assertNotNull(viewModel.uiState.value.error)
        assertNull(store.config)
    }

    @Test
    fun given_endpointWithQuery_when_save_then_errorAndNothingSaved() {
        val viewModel = viewModel()
        viewModel.onEndpointChange("https://hermes.example/sync?token=abc")
        viewModel.onHealthCheckUrlChange(HEALTH_URL)

        viewModel.save(VALID_SECRET)

        assertNotNull(viewModel.uiState.value.error)
        assertNull(store.config)
    }

    @Test
    fun given_noSecretAndNoStoredSecret_when_save_then_secretRequired() {
        val viewModel = viewModel()
        viewModel.onEndpointChange(SYNC_URL)
        viewModel.onHealthCheckUrlChange(HEALTH_URL)

        viewModel.save("")

        assertEquals("secret is required", viewModel.uiState.value.error)
        assertNull(store.config)
    }

    @Test
    fun given_shortSecret_when_save_then_errorAndNothingSaved() {
        val viewModel = viewModel()
        viewModel.onEndpointChange(SYNC_URL)
        viewModel.onHealthCheckUrlChange(HEALTH_URL)
        val shortSecret = "too-short"

        viewModel.save(shortSecret)

        assertEquals("secret must be at least 32 bytes", viewModel.uiState.value.error)
        assertNull(store.config)
    }

    @Test
    fun given_validFormAndSecret_when_save_then_storeHasConfigAndSecretIsPresent() {
        val viewModel = viewModel()
        viewModel.onEndpointChange(SYNC_URL)
        viewModel.onHealthCheckUrlChange(HEALTH_URL)

        viewModel.save(VALID_SECRET)

        assertEquals(
            HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL),
            store.config,
        )
        assertTrue(viewModel.uiState.value.hasSecret)
        assertTrue(viewModel.uiState.value.isSaved)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun given_validFormAndPreviousSecret_when_saveWithoutSecret_then_configIsSaved() {
        val viewModel = viewModel()
        secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
        viewModel.onEndpointChange(SYNC_URL)
        viewModel.onHealthCheckUrlChange(HEALTH_URL)

        viewModel.save("")

        assertNotNull(store.config)
        assertTrue(viewModel.uiState.value.hasSecret)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun given_savedConfigAndSecret_when_disconnect_then_bothAreCleared() {
        val viewModel = viewModel()
        secretManager.importSecret(VALID_SECRET.toByteArray(StandardCharsets.UTF_8))
        store.config = HermesConfig(endpoint = SYNC_URL, healthCheckUrl = HEALTH_URL)

        viewModel.disconnect()

        assertNull(store.config)
        assertFalse(secretManager.hasSecret())
        assertEquals(SyncSettingsUiState(), viewModel.uiState.value)
    }

    @Test
    fun given_secret_when_save_then_stateNeverContainsTheSecret() {
        val viewModel = viewModel()
        viewModel.onEndpointChange(SYNC_URL)
        viewModel.onHealthCheckUrlChange(HEALTH_URL)

        viewModel.save(VALID_SECRET)

        val renderedState = viewModel.uiState.value.toString()
        assertFalse(renderedState.contains(VALID_SECRET))
    }

    private fun viewModel(): SyncSettingsViewModel {
        store = InMemorySyncConfigStore()
        secretManager =
            HmacSecretManager(
                sealer = IdentitySecretSealer(),
                storage = InMemorySecretStorage(),
            )
        return SyncSettingsViewModel(store, secretManager)
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

    private companion object {
        const val SYNC_URL = "https://hermes.example/sync"
        const val HEALTH_URL = "https://hermes.example/health"
        const val VALID_SECRET = "0123456789abcdef0123456789abcdef"
    }
}
