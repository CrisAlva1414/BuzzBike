package com.buzzbike.app.domain

/**
 * Lower bound of the valid percentage range.
 */
private const val PERCENT_MIN = 0.0

/**
 * Upper bound of the valid percentage range.
 */
private const val PERCENT_MAX = 100.0

/**
 * Clamp a percentage into the inclusive range [0.0, 100.0].
 *
 * Placeholder pure function — exists solely to prove the JVM unit-test
 * pipeline works (`./gradlew test`) with zero Android dependencies.
 *
 * NO physical or physiological formulas live in `domain/` yet. Calorie
 * cascade (ADR-001), BMR, METs and wear ratios arrive in slice 5 with
 * sources cited in KDoc + ADR per AGENTS.md §6. Do not add constants here
 * without a published source.
 *
 * NaN is treated as PERCENT_MIN.
 *
 * @param value raw percentage, may be out of range
 * @return value coerced into [0.0, 100.0]
 */
fun clampPercent(value: Double): Double = if (value.isNaN()) PERCENT_MIN else value.coerceIn(PERCENT_MIN, PERCENT_MAX)
