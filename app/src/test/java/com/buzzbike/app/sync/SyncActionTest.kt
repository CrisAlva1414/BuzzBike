package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.ProcessOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [toSyncAction].
 *
 * These pin the worker retry policy: a successful or empty pass is done, a
 * partial batch or unavailable backend must retry, and a configuration error
 * is terminal so WorkManager does not burn attempts on an unfixable request.
 *
 * [HermesWorker] and [HermesSyncScheduler] are intentionally not tested here:
 * both need an Android Context and a live WorkManager runtime, so they belong
 * in instrumentation tests.
 */
class SyncActionTest {
    @Test
    fun given_sentOutcome_when_toSyncAction_then_success() {
        assertEquals(SyncAction.SUCCESS, ProcessOutcome.Sent(count = 2).toSyncAction())
    }

    @Test
    fun given_noPendingOutcome_when_toSyncAction_then_success() {
        assertEquals(SyncAction.SUCCESS, ProcessOutcome.NoPending.toSyncAction())
    }

    @Test
    fun given_partialOutcome_when_toSyncAction_then_retry() {
        assertEquals(SyncAction.RETRY, ProcessOutcome.Partial(sent = 1, failed = 2).toSyncAction())
    }

    @Test
    fun given_backendUnavailableOutcome_when_toSyncAction_then_retry() {
        assertEquals(SyncAction.RETRY, ProcessOutcome.BackendUnavailable.toSyncAction())
    }

    @Test
    fun given_configurationErrorOutcome_when_toSyncAction_then_failure() {
        assertEquals(SyncAction.FAILURE, ProcessOutcome.ConfigurationError.toSyncAction())
    }
}
