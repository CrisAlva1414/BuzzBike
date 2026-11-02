package com.buzzbike.app.core

import kotlinx.coroutines.CancellationException

/**
 * Like [kotlin.runCatching], but never swallows coroutine cancellation.
 *
 * A [CancellationException] is control flow, not a recoverable failure: capturing
 * it would leave the coroutine marked as active while the caller believes it
 * failed, breaking structured concurrency (the parent would not cancel). Every
 * other [Throwable] is still captured as [Result.failure], matching
 * [kotlin.runCatching] semantics.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    val result = runCatching(block)
    val failure = result.exceptionOrNull()
    if (failure is CancellationException) throw failure
    return result
}
