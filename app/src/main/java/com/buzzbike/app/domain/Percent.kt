package com.buzzbike.app.domain

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
 * @param value raw percentage, may be out of range
 * @return value coerced into [0.0, 100.0]
 */
fun clampPercent(value: Double): Double = value.coerceIn(0.0, 100.0)
