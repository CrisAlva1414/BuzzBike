package com.buzzbike.app.domain.sync

import kotlinx.coroutines.CancellationException

/** Result of one [OutboxProcessor.process] run. */
sealed interface ProcessOutcome {
    /** Every eligible row was delivered across all drained batches. */
    data class Sent(
        val count: Int,
    ) : ProcessOutcome

    /** The run mixed successful and failed deliveries. */
    data class Partial(
        val sent: Int,
        val failed: Int,
    ) : ProcessOutcome

    /** The outbox had no row eligible for delivery. */
    data object NoPending : ProcessOutcome

    /** The health check failed; pending rows stay untouched for a retry. */
    data object BackendUnavailable : ProcessOutcome

    /** The health check URL is missing or invalid; pending rows stay untouched. */
    data object ConfigurationError : ProcessOutcome

    /**
     * The drain reached [OutboxProcessorConfig.maxBatches] and at least one row
     * is still eligible. The caller should schedule another pass instead of
     * treating the run as finished.
     */
    data object MorePending : ProcessOutcome
}

private const val DEFAULT_BATCH_SIZE = 10
private const val MAX_BATCHES = 100

/** Exhausted failures are kept for 7 days before being purged. */
private const val FAILED_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * Tuning knobs for [OutboxProcessor]. Defaults preserve the original behavior.
 *
 * @property batchSize Maximum rows read from the store per batch.
 * @property maxBatches Maximum number of batches drained in one run before
 * reporting [ProcessOutcome.MorePending].
 * @property failedRetentionMillis How long exhausted failures are kept before
 * being purged.
 */
data class OutboxProcessorConfig(
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val maxBatches: Int = MAX_BATCHES,
    val failedRetentionMillis: Long = FAILED_RETENTION_MILLIS,
)

/**
 * Drains the sync outbox batch after batch until nothing eligible remains.
 *
 * Order is deliberate and fail-closed:
 * 1. A blank [process] URL is a configuration error — no pending row is
 *    delivered, but delivered and stale exhausted rows are still purged.
 * 2. A non-HTTPS URL is also a configuration error before any network call.
 *    This is defence in depth: [ConnectivityChecker] implementations are
 *    scheme-agnostic so tests can use local servers, but production sync must
 *    never reach a cleartext endpoint (AGENTS.md §7). The URL is still
 *    validated by [HermesConfig] at the worker boundary.
 * 3. The backend must answer the authenticated health check with HTTP 200
 *    exactly. [Reachability.Misconfigured] (missing secret, bad auth or
 *    malformed URL) is terminal; [Reachability.Unavailable] leaves pending
 *    rows for a retry. Both early outcomes still purge delivered and stale
 *    exhausted rows, never `PENDING` ones.
 * 4. Only then are due rows read and delivered, repeating until the eligible
 *    queue is empty.
 *
 * Delivery failures never abort a batch: each row is marked failed (with its
 * own backoff deadline) or sent, and processing continues with the rest.
 * Two responses are special: `429` (rate limited) leaves the row and the rest
 * of the batch pending for a later pass, and `401`/`403` are terminal
 * configuration errors — the same rejected secret cannot succeed on retry, so
 * the drain stops without consuming an attempt for that row.
 * [CancellationException] is the only exception allowed to escape.
 *
 * Two loop guards keep a misbehaving store from spinning forever: the run
 * stops after [OutboxProcessorConfig.maxBatches] batches, and it stops as soon
 * as a batch repeats the exact same ids as the previous one (no progress). A
 * new `now` is read before every batch so a row scheduled with a fresh backoff
 * deadline is not immediately retried in the same run.
 *
 * After the drain (with or without failures) delivered rows and exhausted
 * failures older than [OutboxProcessorConfig.failedRetentionMillis] are purged.
 * `PENDING` rows are never purged.
 */
class OutboxProcessor(
    private val store: OutboxStore,
    private val connectivity: ConnectivityChecker,
    private val sender: HermesSender,
    private val backoff: BackoffPolicy = ExponentialBackoff(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: OutboxProcessorConfig = OutboxProcessorConfig(),
) {
    /**
     * Drains every eligible outbox row.
     *
     * [hmacKeyConfigured] is forwarded to the connectivity port: the health
     * check is authenticated, so a missing secret is a configuration error
     * rather than a transient backend failure.
     */
    suspend fun process(
        healthCheckUrl: String,
        hmacKeyConfigured: Boolean,
    ): ProcessOutcome {
        if (healthCheckUrl.isBlank() || !healthCheckUrl.startsWith(HTTPS_SCHEME, ignoreCase = true)) {
            // Early exits still purge delivered rows and stale exhausted
            // failures: a misconfiguration must not freeze already-terminal
            // rows forever. PENDING rows are never touched by purge().
            purge(clock())
            return ProcessOutcome.ConfigurationError
        }
        return when (connectivity.check(healthCheckUrl, hmacKeyConfigured)) {
            Reachability.Misconfigured -> {
                purge(clock())
                ProcessOutcome.ConfigurationError
            }
            Reachability.Unavailable -> {
                purge(clock())
                ProcessOutcome.BackendUnavailable
            }
            Reachability.Available -> drain()
        }
    }

    private suspend fun drain(): ProcessOutcome {
        var totalSent = 0
        var totalFailed = 0
        var batches = 0
        var previousIds: List<String>? = null
        var drained = false

        while (batches < config.maxBatches) {
            val now = clock()
            val pending = store.getPending(now, config.batchSize)
            val ids = pending.map(OutboxItem::id)
            // Either the queue is empty, or the same rows came back without
            // any state change: more iterations would spin forever.
            if (pending.isEmpty() || ids == previousIds) {
                purge(now)
                drained = true
                break
            }
            previousIds = ids

            for (item in pending) {
                val delivery = deliver(item, now)
                when (delivery) {
                    DeliveryOutcome.SENT -> totalSent++
                    DeliveryOutcome.FAILED -> totalFailed++
                    DeliveryOutcome.RATE_LIMITED,
                    DeliveryOutcome.AUTH_FAILED,
                    -> {
                        // 429 asks us to back off; 401/403 mean the rejected
                        // secret cannot succeed on retry. Both stop the drain
                        // without consuming an attempt: a rate-limited batch is
                        // retried later, an auth failure is reported as a
                        // terminal configuration error.
                        purge(now)
                        return earlyOutcome(delivery)
                    }
                }
            }
            batches++
        }

        return if (drained) {
            outcome(totalSent, totalFailed)
        } else {
            val now = clock()
            purge(now)
            val stillEligible = store.getPending(now, ELIGIBILITY_PROBE_LIMIT).isNotEmpty()
            if (stillEligible) ProcessOutcome.MorePending else outcome(totalSent, totalFailed)
        }
    }

    /** Maps a non-retryable-per-row delivery result to the run outcome. */
    private fun earlyOutcome(delivery: DeliveryOutcome): ProcessOutcome =
        if (delivery == DeliveryOutcome.AUTH_FAILED) {
            ProcessOutcome.ConfigurationError
        } else {
            ProcessOutcome.BackendUnavailable
        }

    private fun outcome(
        sent: Int,
        failed: Int,
    ): ProcessOutcome =
        when {
            failed > 0 -> ProcessOutcome.Partial(sent = sent, failed = failed)
            sent > 0 -> ProcessOutcome.Sent(count = sent)
            else -> ProcessOutcome.NoPending
        }

    /** Removes delivered rows and stale exhausted failures; never pending rows. */
    private suspend fun purge(nowMillis: Long) {
        store.deleteSent()
        store.deleteFailedOlderThan(nowMillis - config.failedRetentionMillis)
    }

    private suspend fun deliver(
        item: OutboxItem,
        now: Long,
    ): DeliveryOutcome =
        try {
            val result = sender.sendRide(item.rideId, item.payload)
            when {
                result.success -> {
                    store.markSent(item.id, now)
                    DeliveryOutcome.SENT
                }
                result.statusCode == HTTP_TOO_MANY_REQUESTS -> DeliveryOutcome.RATE_LIMITED
                result.statusCode == HTTP_UNAUTHORIZED || result.statusCode == HTTP_FORBIDDEN ->
                    DeliveryOutcome.AUTH_FAILED
                else -> {
                    markFailed(item, now, result.errorMessage ?: SEND_FAILED_MESSAGE)
                    DeliveryOutcome.FAILED
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expectedFailure: Exception) {
            markFailed(item, now, expectedFailure.message ?: SEND_FAILED_MESSAGE)
            DeliveryOutcome.FAILED
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

    private enum class DeliveryOutcome {
        SENT,
        FAILED,
        RATE_LIMITED,
        AUTH_FAILED,
    }

    private companion object {
        const val HTTPS_SCHEME = "https://"
        const val ELIGIBILITY_PROBE_LIMIT = 1
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val SEND_FAILED_MESSAGE = "send failed"
    }
}
