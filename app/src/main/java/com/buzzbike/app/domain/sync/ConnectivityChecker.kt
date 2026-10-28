package com.buzzbike.app.domain.sync

/**
 * Port that answers whether a Hermes backend is reachable right now.
 *
 * The domain only knows the question; the transport (HTTP, timeouts, TLS)
 * lives in `sync/` as an edge adapter (AGENTS.md §3). Keeping the port here
 * lets the outbox decide whether to attempt a send without importing
 * `java.net` or Android classes into the domain layer.
 *
 * Contract: a ride is only sent when the check returns `true`. Any other
 * outcome — non-200 response, timeout, DNS failure, refused connection — must
 * be reported as `false` so the ride stays in the outbox for a later retry.
 */
interface ConnectivityChecker {
    /**
     * Returns `true` only when [healthCheckUrl] answers HTTP 200 exactly.
     *
     * Implementations must not throw: expected network problems are part of
     * the answer (`false`), not an exception for the caller to handle.
     */
    suspend fun isReachable(healthCheckUrl: String): Boolean
}
