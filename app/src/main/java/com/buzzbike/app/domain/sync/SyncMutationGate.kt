package com.buzzbike.app.domain.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide owner of the sync mutation lock and revocation epoch.
 *
 * Every read-modify-write over the optional sync configuration must run inside
 * [withLock]: the settings screen (load/save/disconnect) and the post-ride
 * enqueue path share this single gate, so a disconnect cannot interleave with
 * a save or a ride enqueue that started in another instance.
 *
 * [revoke] is synchronous on purpose: callers bump the epoch before waiting for
 * the lock, and any operation that captured an older epoch via [currentEpoch]
 * must abort instead of resurrecting the config, secret or queued work.
 */
class SyncMutationGate {
    private val mutex = Mutex()
    private val epoch = AtomicLong(0)

    /** Snapshot used by an operation to detect a later [revoke]. */
    fun currentEpoch(): Long = epoch.get()

    /** Marks every in-flight operation with an older epoch as stale. */
    fun revoke() {
        epoch.incrementAndGet()
    }

    /** Returns `true` when a [revoke] happened after [epochAtStart] was captured. */
    fun isRevokedSince(epochAtStart: Long): Boolean = epochAtStart < epoch.get()

    /** Serializes a sync mutation; nested calls through this gate are not supported. */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
