package com.buzzbike.app.domain.sync

import kotlin.math.pow

/**
 * Decides how long a failed outbox row waits before its next attempt.
 *
 * This is a product retry policy, not a physiological constant, so it does
 * not require an ADR (AGENTS.md §6). The domain keeps the policy abstract so
 * tests can pin the scheduling without waiting real time.
 */
interface BackoffPolicy {
    /**
     * Returns the delay in milliseconds for the next attempt after
     * [attemptCount] failures (0 means the first attempt just failed).
     */
    fun delayMillis(attemptCount: Int): Long
}

/**
 * Exponential backoff capped at [maxMillis].
 *
 * `delay = min(baseMillis * multiplier^attemptCount, maxMillis)`.
 * Negative attempts are treated as the first failure (base delay), and the
 * exponent is clamped to [MAX_ATTEMPT_COUNT] so an extreme counter can never
 * overflow the computation.
 */
class ExponentialBackoff(
    private val baseMillis: Long = DEFAULT_BASE_MILLIS,
    private val maxMillis: Long = DEFAULT_MAX_MILLIS,
    private val multiplier: Double = DEFAULT_MULTIPLIER,
) : BackoffPolicy {
    override fun delayMillis(attemptCount: Int): Long {
        val exponent = attemptCount.coerceIn(0, MAX_ATTEMPT_COUNT)
        val delay = baseMillis * multiplier.pow(exponent)
        return delay.coerceAtMost(maxMillis.toDouble()).toLong()
    }

    private companion object {
        const val DEFAULT_BASE_MILLIS = 30_000L
        const val DEFAULT_MAX_MILLIS = 3_600_000L
        const val DEFAULT_MULTIPLIER = 2.0
        const val MAX_ATTEMPT_COUNT = 20
    }
}
