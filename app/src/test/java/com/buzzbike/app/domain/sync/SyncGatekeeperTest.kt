package com.buzzbike.app.domain.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the outbox consent check.
 *
 * `disconnect()` bumps the revocation epoch before it finishes clearing the
 * persisted configuration and secret. A drain that captured the epoch first
 * must stop even while both are still readable, otherwise the worker can win
 * the gate race and deliver one extra ride.
 */
class SyncGatekeeperTest {
    private val gate = SyncMutationGate()
    private val configStore = FakeSyncConfigStore(CONFIG)
    private val secretStorage = FakeSecretStorage(byteArrayOf(1))
    private val gatekeeper = gatekeeper()

    @Test
    fun given_epochCapturedThenRevokedWithoutCleanup_when_isSyncAllowed_thenFalse() =
        runTest {
            val startEpoch = gatekeeper.currentEpoch()

            gate.revoke()

            // Config and secret are deliberately left in place: this is the
            // window where disconnect() bumped the epoch but has not cleared
            // the persisted state yet.
            assertFalse(gatekeeper.isSyncAllowed(startEpoch))
            assertTrue(configStore.current() != null)
            assertTrue(secretStorage.readSealed() != null)
        }

    @Test
    fun given_epochCapturedAfterRevoke_when_isSyncAllowed_thenTrue() =
        runTest {
            gate.revoke()

            // A later drain captures the new epoch, so the revocation does not
            // permanently disable sync.
            assertTrue(gatekeeper.isSyncAllowed(gatekeeper.currentEpoch()))
        }

    @Test
    fun given_consentPresentAndEpochUnchanged_when_isSyncAllowed_thenTrue() =
        runTest {
            assertTrue(gatekeeper.isSyncAllowed(gatekeeper.currentEpoch()))
        }

    @Test
    fun given_missingConfig_when_isSyncAllowed_thenFalse() =
        runTest {
            configStore.config = null

            assertFalse(gatekeeper.isSyncAllowed(gatekeeper.currentEpoch()))
        }

    @Test
    fun given_missingSecret_when_isSyncAllowed_thenFalse() =
        runTest {
            secretStorage.clear()

            assertFalse(gatekeeper.isSyncAllowed(gatekeeper.currentEpoch()))
        }

    private fun gatekeeper(): SyncGatekeeper =
        SyncGatekeeper(
            configStore = configStore,
            secretManager = HmacSecretManager(IdentitySecretSealer, secretStorage),
            gate = gate,
        )

    private class FakeSyncConfigStore(
        var config: HermesConfig?,
    ) : SyncConfigStore {
        override fun current(): HermesConfig? = config

        override fun save(config: HermesConfig) {
            this.config = config
        }

        override fun clear() {
            config = null
        }
    }

    private class FakeSecretStorage(
        private var sealed: ByteArray?,
    ) : SecretStorage {
        override fun readSealed(): ByteArray? = sealed?.copyOf()

        override fun writeSealed(sealed: ByteArray) {
            this.sealed = sealed.copyOf()
        }

        override fun clear() {
            sealed = null
        }
    }

    private object IdentitySecretSealer : SecretSealer {
        override fun seal(
            plaintext: ByteArray,
            purpose: String,
        ): ByteArray = plaintext.copyOf()

        override fun unseal(
            sealed: ByteArray,
            purpose: String,
        ): UnsealedSecret = UnsealedSecret(sealed.copyOf(), wasLegacy = false)
    }

    private companion object {
        private val CONFIG = HermesConfig(endpoint = "https://hermes.example.test/webhook")
    }
}
