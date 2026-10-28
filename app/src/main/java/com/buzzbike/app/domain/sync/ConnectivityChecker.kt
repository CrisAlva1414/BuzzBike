package com.buzzbike.app.domain.sync

/** Outcome of a backend reachability probe. */
sealed interface Reachability {
    /** The backend answered the authenticated health check with HTTP 200. */
    data object Available : Reachability

    /** The backend is temporarily unreachable; the outbox must retry later. */
    data object Unavailable : Reachability

    /**
     * The check cannot succeed without a configuration change (missing HMAC
     * secret or malformed URL). Retrying would only burn attempts.
     */
    data object Misconfigured : Reachability
}

/**
 * Port that answers whether a Hermes backend is reachable right now.
 *
 * The domain only knows the question; the transport (HTTP, timeouts, TLS)
 * lives in `sync/` as an edge adapter (AGENTS.md §3). Keeping the port here
 * lets the outbox decide whether to attempt a send without importing
 * `java.net` or Android classes into the domain layer.
 *
 * Contract: a ride is only sent when the check returns
 * [Reachability.Available]. [Reachability.Unavailable] leaves the ride in the
 * outbox for a later retry, while [Reachability.Misconfigured] is terminal
 * until the user fixes the configuration.
 */
interface ConnectivityChecker {
    /**
     * Probes [healthCheckUrl] with an authenticated health check.
     *
     * Implementations must not throw: expected network problems are part of
     * the answer, not an exception for the caller to handle. A missing HMAC
     * secret or a blank/malformed URL must answer
     * [Reachability.Misconfigured] without touching the network.
     */
    suspend fun check(
        healthCheckUrl: String,
        hmacKeyConfigured: Boolean,
    ): Reachability
}
