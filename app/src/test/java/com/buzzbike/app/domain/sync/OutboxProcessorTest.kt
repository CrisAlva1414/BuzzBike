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
 */
class OutboxProcessorTest {
    @Test
    fun given_blankUrl_when_process_then_configurationErrorAndNothingTouched() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A)))
            val connectivity = FakeConnectivityChecker(reachable = true)
            val sender = FakeHermesSender()
            val processor = processor(store = store, connectivity = connectivity, sender = sender)

            val outcome = processor.process("   ")

            assertEquals(ProcessOutcome.ConfigurationError, outcome)
            assertEquals(0, connectivity.calls)
            assertEquals(0, store.getPendingCalls)
            assertTrue(store.sentIds.isEmpty())
            assertTrue(store.failedMarks.isEmpty())
        }

    @Test
    fun given_unreachableBackend_when_process_then_backendUnavailableAndRowsPending() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A)))
            val connectivity = FakeConnectivityChecker(reachable = false)
            val sender = FakeHermesSender()
            val processor = processor(store = store, connectivity = connectivity, sender = sender)

            val outcome = processor.process(HEALTH_URL)

            assertEquals(ProcessOutcome.BackendUnavailable, outcome)
            assertEquals(0, store.getPendingCalls)
            assertTrue(sender.rideIds.isEmpty())
            assertTrue(store.sentIds.isEmpty())
            assertTrue(store.failedMarks.isEmpty())
        }

    @Test
    fun given_twoPendingRows_when_process_then_bothSent() =
        runTest {
            val store = FakeOutboxStore(items = listOf(item(ID_A), item(ID_B)))
            val sender = FakeHermesSender()
            val processor = processor(store = store, sender = sender)

            val outcome = processor.process(HEALTH_URL)

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

            val outcome = processor.process(HEALTH_URL)

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

            val outcome = processor.process(HEALTH_URL)

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

            val outcome = processor.process(HEALTH_URL)

            assertEquals(ProcessOutcome.NoPending, outcome)
            assertEquals(1, store.getPendingCalls)
            assertTrue(sender.rideIds.isEmpty())
        }

    private fun processor(
        store: OutboxStore,
        connectivity: ConnectivityChecker = FakeConnectivityChecker(reachable = true),
        sender: HermesSender = FakeHermesSender(),
    ): OutboxProcessor =
        OutboxProcessor(
            store = store,
            connectivity = connectivity,
            sender = sender,
            clock = { NOW_MILLIS },
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
        const val MAX_ATTEMPTS = 5
        const val SECOND_ATTEMPT_DELAY_MILLIS = 120_000L
        const val HTTP_ERROR = "HTTP 503"
        const val SENDER_ERROR = "boom"
    }
}

/** In-memory [OutboxStore] that records every state transition. */
private class FakeOutboxStore(
    private val items: List<OutboxItem>,
) : OutboxStore {
    var getPendingCalls: Int = 0
        private set
    val sentIds: MutableList<String> = mutableListOf()
    val failedMarks: MutableList<FailedMark> = mutableListOf()

    override suspend fun getPending(
        nowMillis: Long,
        limit: Int,
    ): List<OutboxItem> {
        getPendingCalls++
        return items.take(limit)
    }

    override suspend fun markSent(
        id: String,
        nowMillis: Long,
    ) {
        sentIds += id
    }

    override suspend fun markFailed(
        id: String,
        error: String,
        nowMillis: Long,
        nextAttemptAtMillis: Long,
    ) {
        failedMarks += FailedMark(id, error, nowMillis, nextAttemptAtMillis)
    }
}

private data class FailedMark(
    val id: String,
    val error: String,
    val nowMillis: Long,
    val nextAttemptAtMillis: Long,
)

private class FakeConnectivityChecker(
    private val reachable: Boolean,
) : ConnectivityChecker {
    var calls: Int = 0
        private set

    override suspend fun isReachable(healthCheckUrl: String): Boolean {
        calls++
        return reachable
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
