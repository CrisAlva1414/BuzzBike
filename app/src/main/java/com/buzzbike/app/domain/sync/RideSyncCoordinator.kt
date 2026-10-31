package com.buzzbike.app.domain.sync

import kotlinx.coroutines.CancellationException

/** Result of trying to queue a finished ride for optional Hermes sync. */
sealed interface RideSyncOutcome {
    /** Sync is not configured; nothing was loaded, built or enqueued. */
    data object Disabled : RideSyncOutcome

    /** The ride has no persisted data to send. */
    data object NoData : RideSyncOutcome

    /** The payload is in the outbox and the worker was scheduled. */
    data object Enqueued : RideSyncOutcome

    /** Any step failed. The ride may already be in the outbox (see below). */
    data object Failed : RideSyncOutcome
}

/**
 * Coordinates the post-ride sync pipeline (ADR-003, AGENTS.md §7).
 *
 * Sync is opt-in: with a `null` [SyncConfigStore.current] or no stored secret
 * this coordinator does not touch the data source, so a default install never
 * reads or sends ride data.
 *
 * Both checks happen through [SyncGatekeeper.withConsent], which re-reads the
 * persisted configuration and secret **inside** [SyncMutationGate.withLock]
 * instead of trusting a snapshot captured by the caller. A concurrent
 * disconnect therefore cannot be outrun by a ride that was stopped at the same
 * time: once the disconnect owns the gate, the persisted config and secret are
 * already gone and this coordinator returns [RideSyncOutcome.Disabled].
 *
 * When the payload is enqueued and scheduling then fails, the result is
 * [RideSyncOutcome.Failed] but the row already lives in the outbox and will be
 * delivered by the next worker pass — enqueue is deliberately done before
 * scheduling so a scheduling error never drops the payload.
 *
 * [HermesConfig.retryCount] is forwarded to [OutboxEnqueuer.enqueue] as the
 * row's `maxAttempts` (coerced to at least 1), so the user-facing retry count
 * actually bounds delivery attempts.
 *
 * No payload content is logged here.
 */
class RideSyncCoordinator(
    private val dataSource: RideSyncDataSource,
    private val enqueuer: OutboxEnqueuer,
    private val scheduler: SyncWorkScheduler,
    private val gatekeeper: SyncConsentGate,
) {
    /**
     * Loads, serializes and queues [rideId] for delivery, then schedules work.
     *
     * The configuration and secret are re-read under the shared gate; a ride
     * stopped after a disconnect is a no-op.
     *
     * @return what happened, never throwing for expected failures.
     */
    suspend fun onRideFinished(rideId: String): RideSyncOutcome =
        try {
            gatekeeper.withConsent { config ->
                if (rideId.isBlank()) {
                    RideSyncOutcome.Failed
                } else {
                    attemptEnqueue(rideId, config)
                }
            } ?: RideSyncOutcome.Disabled
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            RideSyncOutcome.Failed
        }

    private suspend fun attemptEnqueue(
        rideId: String,
        config: HermesConfig,
    ): RideSyncOutcome =
        try {
            val input = dataSource.load(rideId)
            if (input == null) {
                RideSyncOutcome.NoData
            } else {
                val payload = RidePayloadJson.encode(RidePayloadBuilder.build(input))
                enqueuer.enqueue(
                    rideId = rideId,
                    payload = payload,
                    maxAttempts = config.retryCount.coerceAtLeast(1),
                )
                scheduler.schedule(config)
                RideSyncOutcome.Enqueued
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            RideSyncOutcome.Failed
        }
}
