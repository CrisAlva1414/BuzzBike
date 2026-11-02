package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.ProcessOutcome

/**
 * What WorkManager should do after one outbox pass.
 *
 * Pure on purpose: mapping [ProcessOutcome] to a retry policy needs no Android
 * classes, so it is unit-tested on the JVM while the worker stays a thin
 * adapter.
 */
enum class SyncAction {
    SUCCESS,
    RETRY,
    FAILURE,
}

/**
 * Maps a domain [ProcessOutcome] to the worker's next action.
 *
 * `Partial`, `MorePending` and `BackendUnavailable` all retry: failed rows
 * stay in the outbox with their own backoff deadline, a capped drain still has
 * eligible rows, and an unavailable backend leaves every row pending.
 * `ConsentRevoked` is a success: the user disconnected, so there is nothing
 * left to deliver and WorkManager must not retry the pass. `ConfigurationError`
 * is terminal — retrying cannot fix a missing endpoint, so it must not burn
 * WorkManager attempts.
 */
fun ProcessOutcome.toSyncAction(): SyncAction =
    when (this) {
        is ProcessOutcome.Sent, ProcessOutcome.NoPending, ProcessOutcome.ConsentRevoked ->
            SyncAction.SUCCESS
        is ProcessOutcome.Partial, ProcessOutcome.MorePending, ProcessOutcome.BackendUnavailable ->
            SyncAction.RETRY
        ProcessOutcome.ConfigurationError -> SyncAction.FAILURE
    }
