package com.buzzbike.app.domain.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [OutboxProcessor].
 *
 * Fakes stand in for the Room store, the connectivity checker and the sender,
 * so the retry state machine is exercised without Android, network, or time.
 * The connectivity fake returns the full [Reachability] contract, which lets
 * these tests pin the difference between a transient outage (retry) and a
 * configuration error (terminal).
 */
class OutboxProcessorTest {
    @Test
    fun given_blankUrl_when_process_then_configurationErrorAndNothingTouched() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A)))
            val connectivity = FakeConnectivityChecker(Reachability.Available)
            val sender = FakeHermesSender()
            val processor = processor(store = store, connectivity = connectivity, sender = sender)

            val outcome = processor.process("   ", hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.ConfigurationError, outcome)
            assertEquals(0, connectivity.calls)
            assertEquals(0, store.getPendingCalls)
            assertTrue(store.sentIds.isEmpty())
            assertTrue(store.failedMarks.isEmpty())
        }

    @Test
    fun given_httpUrl_when_process_then_configurationErrorWithoutNetwork() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A)))
            val connectivity = FakeConnectivityChecker(Reachability.Available)
            val sender = FakeHermesSender()
            val processor = processor(store = store, connectivity = connectivity, sender = sender)

            val outcome = processor.process("http://hermes.example/health", hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.ConfigurationError, outcome)
            assertEquals(0, connectivity.calls)
            assertEquals(0, store.getPendingCalls)
            assertTrue(sender.rideIds.isEmpty())
        }

    @Test
    fun given_httpsUrl_when_process_then_checksReachabilityAndDrains() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A)))
            val connectivity = FakeConnectivityChecker(Reachability.Available)
            val sender = FakeHermesSender()
            val processor = processor(store = store, connectivity = connectivity, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.Sent(1), outcome)
            assertEquals(1, connectivity.calls)
            assertEquals(listOf(ID_A), store.sentIds)
        }

    @Test
    fun given_misconfiguredBackend_when_process_then_configurationErrorAndRowsPending() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A)))
            val connectivity = FakeConnectivityChecker(Reachability.Misconfigured)
            val sender = FakeHermesSender()
            val processor = processor(store = store, connectivity = connectivity, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.ConfigurationError, outcome)
            assertEquals(1, connectivity.calls)
            assertEquals(0, store.getPendingCalls)
            assertTrue(sender.rideIds.isEmpty())
            assertTrue(store.sentIds.isEmpty())
            assertTrue(store.failedMarks.isEmpty())
            assertEquals(1, store.deleteSentCalls)
            assertEquals(listOf(NOW_MILLIS - SEVEN_DAYS_MILLIS), store.deleteFailedCutoffs)
        }

    @Test
    fun given_unavailableBackend_when_process_then_backendUnavailableAndRowsPending() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A)))
            val connectivity = FakeConnectivityChecker(Reachability.Unavailable)
            val sender = FakeHermesSender()
            val processor = processor(store = store, connectivity = connectivity, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.BackendUnavailable, outcome)
            assertEquals(0, store.getPendingCalls)
            assertTrue(sender.rideIds.isEmpty())
            assertTrue(store.sentIds.isEmpty())
            assertTrue(store.failedMarks.isEmpty())
            assertEquals(1, store.deleteSentCalls)
            assertEquals(listOf(NOW_MILLIS - SEVEN_DAYS_MILLIS), store.deleteFailedCutoffs)
            assertEquals(listOf(ID_A), store.getPending(NOW_MILLIS, DEFAULT_BATCH_SIZE).map { it.id })
        }

    @Test
    fun given_availableBackend_when_process_then_forwardsSecretFlagAndSends() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A)))
            val connectivity = FakeConnectivityChecker(Reachability.Available)
            val sender = FakeHermesSender()
            val processor = processor(store = store, connectivity = connectivity, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = false)

            assertEquals(ProcessOutcome.Sent(1), outcome)
            assertEquals(HEALTH_URL, connectivity.lastUrl)
            assertEquals(false, connectivity.lastHmacKeyConfigured)
            assertEquals(listOf(rideIdOf(ID_A)), sender.rideIds)
            assertEquals(listOf(ID_A), store.sentIds)
        }

    @Test
    fun given_twoPendingRows_when_process_then_bothSent() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A), item(ID_B)))
            val sender = FakeHermesSender()
            val processor = processor(store = store, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.Sent(2), outcome)
            assertEquals(listOf(ID_A, ID_B), store.sentIds)
            assertTrue(store.failedMarks.isEmpty())
        }

    @Test
    fun given_oneOkAndOneFailed_when_process_then_partialWithBackoffDeadline() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A), item(ID_B, attemptCount = 2)))
            val sender =
                FakeHermesSender { rideId ->
                    if (rideId == rideIdOf(ID_B)) {
                        SendResult(success = false, errorMessage = HTTP_ERROR)
                    } else {
                        SendResult(success = true)
                    }
                }
            val processor = processor(store = store, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.Partial(sent = 1, failed = 1), outcome)
            assertEquals(listOf(ID_A), store.sentIds)
            val failed = store.failedMarks.single()
            assertEquals(ID_B, failed.id)
            assertEquals(HTTP_ERROR, failed.error)
            assertEquals(NOW_MILLIS, failed.nowMillis)
            assertEquals(NOW_MILLIS + SECOND_ATTEMPT_DELAY_MILLIS, failed.nextAttemptAtMillis)
        }

    @Test
    fun given_senderThrows_when_process_then_marksFailedAndContinues() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A), item(ID_B)))
            val sender =
                FakeHermesSender { rideId ->
                    if (rideId == rideIdOf(ID_A)) {
                        throw IllegalStateException(SENDER_ERROR)
                    }
                    SendResult(success = true)
                }
            val processor = processor(store = store, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.Partial(sent = 1, failed = 1), outcome)
            assertEquals(SENDER_ERROR, store.failedMarks.single().error)
            assertEquals(listOf(ID_B), store.sentIds)
            assertEquals(listOf(rideIdOf(ID_A), rideIdOf(ID_B)), sender.rideIds)
        }

    @Test
    fun given_noPendingRows_when_process_then_noPending() =
        runTest {
            val store = FakeOutboxStore(items = emptyList())
            val sender = FakeHermesSender()
            val processor = processor(store = store, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.NoPending, outcome)
            assertEquals(1, store.getPendingCalls)
            assertTrue(sender.rideIds.isEmpty())
        }

    @Test
    fun given_elevenRows_when_process_then_drainsAllBatchesAndSendsEachOnce() =
        runTest {
            val items = (1..11).map { item("id-$it") }
            val store = FakeOutboxStore(items = items)
            val sender = FakeHermesSender()
            val processor = processor(store = store, sender = sender, batchSize = 10)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.Sent(11), outcome)
            assertEquals(11, sender.rideIds.size)
            assertEquals(11, store.sentIds.size)
            assertEquals(3, store.getPendingCalls)
        }

    @Test
    fun given_twentyFiveRows_when_process_then_allAreSent() =
        runTest {
            val items = (1..25).map { item("id-$it") }
            val store = FakeOutboxStore(items = items)
            val sender = FakeHermesSender()
            val processor = processor(store = store, sender = sender, batchSize = 10)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.Sent(25), outcome)
            assertEquals(25, sender.rideIds.size)
            assertEquals(25, store.sentIds.size)
        }

    @Test
    fun given_oneFailure_when_process_then_partialAndFailedRowNotRetriedInSameRun() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A), item(ID_B), item(ID_C)))
            val sender =
                FakeHermesSender { rideId ->
                    if (rideId == rideIdOf(ID_B)) {
                        SendResult(success = false, errorMessage = HTTP_ERROR)
                    } else {
                        SendResult(success = true)
                    }
                }
            val processor = processor(store = store, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.Partial(sent = 2, failed = 1), outcome)
            assertEquals(listOf(rideIdOf(ID_A), rideIdOf(ID_B), rideIdOf(ID_C)), sender.rideIds)
            val failed = store.failedMarks.single()
            assertEquals(ID_B, failed.id)
            assertTrue(failed.nextAttemptAtMillis > NOW_MILLIS)
        }

    @Test
    fun given_drainFinishes_when_process_then_purgesSentAndStaleFailures() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A)))
            val processor = processor(store = store)

            processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(1, store.deleteSentCalls)
            assertEquals(listOf(NOW_MILLIS - SEVEN_DAYS_MILLIS), store.deleteFailedCutoffs)
        }

    @Test
    fun given_maxBatchesReachedWithRowsLeft_when_process_then_morePending() =
        runTest {
            val items = (1..15).map { item("id-$it") }
            val store = FakeOutboxStore(items = items)
            val sender = FakeHermesSender()
            val processor = processor(store = store, sender = sender, batchSize = 10, maxBatches = 1)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.MorePending, outcome)
            assertEquals(10, sender.rideIds.size)
        }

    @Test
    fun given_storeReturnsSameBatchWithoutProgress_when_process_then_stopsInsteadOfLooping() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A), item(ID_B)), repeatBatch = true)
            val sender = FakeHermesSender()
            val processor = processor(store = store, sender = sender)

            val outcome = processor.process(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.Sent(2), outcome)
            assertEquals(2, sender.rideIds.size)
            assertEquals(2, store.getPendingCalls)
        }

    private fun processor(
        store: OutboxStore,
        connectivity: ConnectivityChecker = FakeConnectivityChecker(Reachability.Available),
        sender: HermesSender = FakeHermesSender(),
        batchSize: Int = DEFAULT_BATCH_SIZE,
        maxBatches: Int = DEFAULT_MAX_BATCHES,
    ): OutboxProcessor =
        OutboxProcessor(
            store = store,
            connectivity = connectivity,
            sender = sender,
            clock = { NOW_MILLIS },
            config =
                OutboxProcessorConfig(
                    batchSize = batchSize,
                    maxBatches = maxBatches,
                ),
        )

    private fun item(
        id: String,
        attemptCount: Int = 0,
    ): OutboxItem =
        OutboxItem(
            id = id,
            rideId = rideIdOf(id),
            payload = ByteArray(0),
            attemptCount = attemptCount,
            maxAttempts = MAX_ATTEMPTS,
        )

    private fun rideIdOf(id: String): String = "ride-$id"

    private companion object {
        const val HEALTH_URL = "https://hermes.example/health"
        const val NOW_MILLIS = 1_000_000L
        const val ID_A = "a"
        const val ID_B = "b"
        const val ID_C = "c"
        const val MAX_ATTEMPTS = 5
        const val DEFAULT_BATCH_SIZE = 10
        const val DEFAULT_MAX_BATCHES = 100
        const val SECOND_ATTEMPT_DELAY_MILLIS = 120_000L
        const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val HTTP_ERROR = "HTTP 503"
        const val SENDER_ERROR = "boom"
    }
}

/**
 * In-memory [OutboxStore] that records every state transition.
 *
 * [repeatBatch] models a misbehaving store that never applies progress, so
 * tests can prove the processor's no-progress guard. Otherwise sent rows are
 * removed and failed rows honor their backoff deadline.
 */
private class FakeOutboxStore(
    items: List<OutboxItem>,
    private val repeatBatch: Boolean = false,
) : OutboxStore {
    private val initialItems = items
    private val remaining = items.associateByTo(LinkedHashMap(), OutboxItem::id)
    private val nextAttemptAt = items.associate { it.id to Long.MIN_VALUE }.toMutableMap()

    var getPendingCalls: Int = 0
        private set
    var deleteSentCalls: Int = 0
        private set
    val sentIds: MutableList<String> = mutableListOf()
    val failedMarks: MutableList<FailedMark> = mutableListOf()
    val deleteFailedCutoffs: MutableList<Long> = mutableListOf()

    override suspend fun getPending(
        nowMillis: Long,
        limit: Int,
    ): List<OutboxItem> {
        getPendingCalls++
        if (repeatBatch) return initialItems.take(limit)
        return remaining.values
            .filter { nextAttemptAt[it.id] ?: Long.MIN_VALUE <= nowMillis }
            .take(limit)
    }

    override suspend fun markSent(
        id: String,
        nowMillis: Long,
    ) {
        sentIds += id
        remaining.remove(id)
    }

    override suspend fun markFailed(
        id: String,
        error: String,
        nowMillis: Long,
        nextAttemptAtMillis: Long,
    ) {
        failedMarks += FailedMark(id, error, nowMillis, nextAttemptAtMillis)
        nextAttemptAt[id] = nextAttemptAtMillis
    }

    override suspend fun deleteSent() {
        deleteSentCalls++
        if (!repeatBatch) {
            sentIds.forEach { remaining.remove(it) }
        }
    }

    override suspend fun deleteFailedOlderThan(cutoffMillis: Long) {
        deleteFailedCutoffs += cutoffMillis
    }
}

private data class FailedMark(
    val id: String,
    val error: String,
    val nowMillis: Long,
    val nextAttemptAtMillis: Long,
)

private class FakeConnectivityChecker(
    private val reachability: Reachability,
) : ConnectivityChecker {
    var calls: Int = 0
        private set
    var lastUrl: String? = null
        private set
    var lastHmacKeyConfigured: Boolean? = null
        private set

    override suspend fun check(
        healthCheckUrl: String,
        hmacKeyConfigured: Boolean,
    ): Reachability {
        calls++
        lastUrl = healthCheckUrl
        lastHmacKeyConfigured = hmacKeyConfigured
        return reachability
    }
}

private class FakeHermesSender(
    private val onSend: (String) -> SendResult = { SendResult(success = true) },
) : HermesSender {
    val rideIds: MutableList<String> = mutableListOf()

    override suspend fun sendRide(
        rideId: String,
        payload: ByteArray,
    ): SendResult {
        rideIds += rideId
        return onSend(rideId)
    }
}
