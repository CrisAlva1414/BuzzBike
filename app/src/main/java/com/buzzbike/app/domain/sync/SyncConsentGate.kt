package com.buzzbike.app.domain.sync

/**
 * Read side of the sync consent check shared by every sync consumer.
 *
 * Implementations must re-read the persisted configuration and secret while
 * holding [SyncMutationGate], so a concurrent revoke cannot be outrun by an
 * operation that captured consent earlier.
 */
interface SyncConsentGate {
    /**
     * Runs [block] with the configuration re-read under the gate when sync is
     * enabled, or returns `null` when sync is disabled.
     */
    suspend fun <T> withConsent(block: suspend (HermesConfig) -> T): T?
}
