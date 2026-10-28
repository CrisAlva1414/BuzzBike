package com.buzzbike.app.domain.sync

import kotlinx.coroutines.CancellationException

/** Result of one [OutboxProcessor.process] run. */
sealed interface ProcessOutcome {
    /** Every pending row in the batch was delivered. */
    data class Sent(
        val count: Int,
    ) : ProcessOutcome

    /** The batch mixed successful and failed deliveries. */
    data class Partial(
        val sent: Int,
        val failed: Int,
    ) : ProcessOutcome

    /** The outbox had no row eligible for delivery. */
    data object NoPending : ProcessOutcome

    /** The health check failed; rows are untouched and stay pending. */
    data object BackendUnavailable : ProcessOutcome

    /** The health check URL is missing; rows are untouched. */
    data object ConfigurationError : ProcessOutcome
}

/**
 * Processes one batch of the sync outbox.
 *
 * Order is deliberate and fail-closed:
 * 1. A blank [process] URL is a configuration error — nothing is touched.
 * 2. The backend must answer the authenticated health check with HTTP 200
 *    exactly. [Reachability.Misconfigured] (missing secret or malformed URL)
 *    is terminal; [Reachability.Unavailable] leaves rows pending for a retry.
 * 3. Only then are due rows read and delivered.
 *
 * Delivery failures never abort the batch: each row is marked failed (with
 * its own backoff deadline) or sent, and processing continues with the rest.
 * [CancellationException] is the only exception allowed to escape.
 */
class OutboxProcessor(
    private val store: OutboxStore,
    private val connectivity: ConnectivityChecker,
    private val sender: HermesSender,
    private val backoff: BackoffPolicy = ExponentialBackoff(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    /**
     * Runs one outbox pass.
     *
     * [hmacKeyConfigured] is forwarded to the connectivity port: the health
     * check is authenticated, so a missing secret is a configuration error
     * rather than a transient backend failure.
     */
    suspend fun process(
        healthCheckUrl: String,
        hmacKeyConfigured: Boolean,
    ): ProcessOutcome {
        if (healthCheckUrl.isBlank()) return ProcessOutcome.ConfigurationError
        return when (connectivity.check(healthCheckUrl, hmacKeyConfigured)) {
            Reachability.Misconfigured -> ProcessOutcome.ConfigurationError
            Reachability.Unavailable -> ProcessOutcome.BackendUnavailable
            Reachability.Available -> processPending()
        }
    }

    private suspend fun processPending(): ProcessOutcome {
        val now = clock()
        val pending = store.getPending(now, batchSize)
        if (pending.isEmpty()) return ProcessOutcome.NoPending

        var sent = 0
        var failed = 0
        for (item in pending) {
            if (deliver(item, now)) {
                sent++
            } else {
                failed++
            }
        }
        return if (failed == 0) ProcessOutcome.Sent(sent) else ProcessOutcome.Partial(sent, failed)
    }

    private suspend fun deliver(
        item: OutboxItem,
        now: Long,
    ): Boolean =
        try {
            val result = sender.sendRide(item.rideId, item.payload)
            if (result.success) {
                store.markSent(item.id, now)
                true
            } else {
                markFailed(item, now, result.errorMessage ?: SEND_FAILED_MESSAGE)
                false
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expectedFailure: Exception) {
            markFailed(item, now, expectedFailure.message ?: SEND_FAILED_MESSAGE)
            false
        }

    private suspend fun markFailed(
        item: OutboxItem,
        now: Long,
        error: String,
    ) {
        store.markFailed(
            id = item.id,
            error = error,
            nowMillis = now,
            nextAttemptAtMillis = now + backoff.delayMillis(item.attemptCount),
        )
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 10
        const val SEND_FAILED_MESSAGE = "send failed"
    }
}
