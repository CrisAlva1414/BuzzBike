package com.buzzbike.app.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-pure tests for [ExponentialBackoff].
 *
 * Pins the product rule: base delay on the first failure, doubling per
 * attempt, a hard cap, and no overflow on absurd counters.
 */
class ExponentialBackoffTest {
    private val backoff = ExponentialBackoff()

    @Test
    fun given_firstFailure_when_delayMillis_then_baseDelay() {
        assertEquals(BASE_MILLIS, backoff.delayMillis(0))
    }

    @Test
    fun given_secondFailure_when_delayMillis_then_doubles() {
        assertEquals(BASE_MILLIS * 2, backoff.delayMillis(1))
    }

    @Test
    fun given_largeAttempt_when_delayMillis_then_cappedAtMax() {
        assertEquals(MAX_MILLIS, backoff.delayMillis(20))
    }

    @Test
    fun given_negativeAttempt_when_delayMillis_then_baseDelay() {
        assertEquals(BASE_MILLIS, backoff.delayMillis(-3))
    }

    @Test
    fun given_extremeAttempt_when_delayMillis_then_cappedWithoutOverflow() {
        assertEquals(MAX_MILLIS, backoff.delayMillis(Int.MAX_VALUE))
    }

    private companion object {
        const val BASE_MILLIS = 30_000L
        const val MAX_MILLIS = 3_600_000L
    }
}
