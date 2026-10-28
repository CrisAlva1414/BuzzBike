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
 * Sync is opt-in: with a `null` [HermesConfig] this coordinator does not touch
 * the data source, so a default install never reads or sends ride data.
 *
 * When the payload is enqueued and scheduling then fails, the result is
 * [RideSyncOutcome.Failed] but the row already lives in the outbox and will be
 * delivered by the next worker pass — enqueue is deliberately done before
 * scheduling so a scheduling error never drops the payload.
 *
 * No payload content is logged here.
 */
class RideSyncCoordinator(
    private val dataSource: RideSyncDataSource,
    private val enqueuer: OutboxEnqueuer,
    private val scheduler: SyncWorkScheduler,
) {
    /**
     * Loads, serializes and queues [rideId] for delivery, then schedules work.
     *
     * @param config current sync configuration, or `null` when sync is off.
     * @return what happened, never throwing for expected failures.
     */
    suspend fun onRideFinished(
        rideId: String,
        config: HermesConfig?,
    ): RideSyncOutcome {
        if (config == null) return RideSyncOutcome.Disabled
        return if (rideId.isBlank()) {
            RideSyncOutcome.Failed
        } else {
            attemptEnqueue(rideId, config)
        }
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
                enqueuer.enqueue(rideId, payload)
                scheduler.schedule(config)
                RideSyncOutcome.Enqueued
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            RideSyncOutcome.Failed
        }
}
