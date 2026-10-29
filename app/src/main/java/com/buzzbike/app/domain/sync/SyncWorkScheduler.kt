package com.buzzbike.app.domain.sync

/**
 * Port that asks the platform to run a sync pass for [config].
 *
 * Scheduling is fire-and-forget: the adapter enqueues background work and
 * returns without waiting for delivery. Failures while enqueueing propagate
 * so [RideSyncCoordinator] can report them.
 */
interface SyncWorkScheduler {
    fun schedule(config: HermesConfig)

    /**
     * Cancels every queued or running sync pass.
     *
     * Used when sync is disconnected: leaving work enqueued would keep
     * attempting deliveries after the user revoked the configuration.
     */
    fun cancel()
}
