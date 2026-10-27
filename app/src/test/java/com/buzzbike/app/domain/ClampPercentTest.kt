package com.buzzbike.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-pure tests for [clampPercent] — no Robolectric, no instrumented runner
 * (AGENTS.md §8: `domain/` must be testable on the JVM).
 *
 * Naming follows docs/format/estilo-kotlin.md §9:
 * `given_<setup>_when_<action>_then_<expected>()`.
 */
class ClampPercentTest {
    @Test
    fun given_negativeValue_when_clamped_then_zero() {
        assertEquals(0.0, clampPercent(-5.0), 0.0)
    }

    @Test
    fun given_valueAboveRange_when_clamped_then_hundred() {
        assertEquals(100.0, clampPercent(150.0), 0.0)
    }

    @Test
    fun given_valueInRange_when_clamped_then_unchanged() {
        assertEquals(42.5, clampPercent(42.5), 0.0)
    }

    @Test
    fun given_boundaryLow_when_clamped_then_zero() {
        assertEquals(0.0, clampPercent(0.0), 0.0)
    }

    @Test
    fun given_boundaryHigh_when_clamped_then_hundred() {
        assertEquals(100.0, clampPercent(100.0), 0.0)
    }

    @Test
    fun given_nan_when_clamped_then_min() {
        assertEquals(0.0, clampPercent(Double.NaN), 0.0)
    }
}
