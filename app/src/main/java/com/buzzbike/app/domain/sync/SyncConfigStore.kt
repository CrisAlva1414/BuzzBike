package com.buzzbike.app.domain.sync

/**
 * Persists the non-secret part of the optional Hermes sync configuration.
 *
 * The HMAC secret is deliberately **not** part of this port: it has its own
 * sealed storage managed by [HmacSecretManager] (ADR-003, AGENTS.md §7).
 *
 * `current() == null` means sync is disabled. An implementation must return
 * `null` rather than invent missing destinations.
 */
interface SyncConfigStore {
    /** Returns the stored configuration, or `null` when sync is disabled. */
    fun current(): HermesConfig?

    /** Persists [config], replacing any previous value. */
    fun save(config: HermesConfig)

    /** Removes the stored configuration, disabling sync. */
    fun clear()
}
