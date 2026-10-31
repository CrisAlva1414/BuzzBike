package com.buzzbike.app.domain.sync

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-reads sync consent (configuration + stored secret) **inside** the shared
 * [SyncMutationGate].
 *
 * Every consumer that acts on the persisted sync state must go through
 * [withConsent] instead of trusting a snapshot captured by its caller. Because
 * the reads happen while the gate is held, a concurrent disconnect — which
 * clears the secret and configuration under the same gate — cannot be outrun:
 * the operation observes the revoked state and returns `null`.
 *
 * The [SyncMutationGate] instance is process-wide, so the settings screen and
 * the post-ride enqueue path share the same lock and revocation epoch.
 */
@Singleton
class SyncGatekeeper
    @Inject
    constructor(
        private val configStore: SyncConfigStore,
        private val secretManager: HmacSecretManager,
        private val gate: SyncMutationGate,
    ) : SyncConsentGate {
        /**
         * Runs [block] with the configuration re-read under the gate when sync
         * is enabled (a configuration exists **and** a secret is stored).
         *
         * @return `null` when sync is disabled, otherwise whatever [block]
         * returns. Exceptions thrown by [block] propagate unchanged.
         */
        override suspend fun <T> withConsent(block: suspend (HermesConfig) -> T): T? =
            gate.withLock {
                val config = configStore.current()
                if (config == null || !secretManager.hasSecret()) {
                    null
                } else {
                    block(config)
                }
            }
    }
