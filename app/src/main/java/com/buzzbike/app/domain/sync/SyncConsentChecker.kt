package com.buzzbike.app.domain.sync

/**
 * Read-only consent check used by the outbox drain before every send.
 *
 * Implementations must re-read the persisted configuration and secret while
 * holding [SyncMutationGate], then release the lock **before returning**. This
 * is deliberately narrower than [SyncConsentGate.withConsent]: the drain calls
 * [isSyncAllowed] and only afterwards performs the HTTP request, so the gate is
 * never held across network I/O.
 *
 * The check also consults the [SyncMutationGate] revocation epoch: a drain
 * captures [currentEpoch] once at the start and passes it to every
 * [isSyncAllowed] call, so a `disconnect()` that revokes while the persisted
 * configuration and secret are still being cleared cannot let another ride
 * through.
 *
 * The check only guarantees the state at the instant it ran. A concurrent
 * disconnect can still win the race against an already in-flight request, but
 * it prevents every subsequent row from being sent after consent is revoked.
 */
interface SyncConsentChecker {
    /** Snapshot of the revocation epoch, captured once before a drain starts. */
    fun currentEpoch(): Long

    /**
     * Returns `true` only when sync is enabled, a secret is stored **and** no
     * [SyncMutationGate.revoke] happened since [epochAtStart] was captured.
     */
    suspend fun isSyncAllowed(epochAtStart: Long): Boolean
}
