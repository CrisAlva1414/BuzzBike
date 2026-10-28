package com.buzzbike.app.domain.calories

/**
 * Calorie estimate expressed as a product band, never as an exact number.
 *
 * ADR-001 is explicit: these percentages are **product bands**, not statistical
 * confidence intervals. [estimateKcal] is only the centre used for display; the
 * UI must always show `[lowKcal, highKcal]` together with [model]
 * (AGENTS.md §6.4).
 */
data class CalorieSummary(
    val estimateKcal: Double,
    val lowKcal: Double,
    val highKcal: Double,
    val model: String,
)

/**
 * Converts a calculator [CalorieResult] into a transportable [CalorieSummary].
 *
 * Both sides of the band use the **widest** percentage
 * ([CalorieResult.uncertaintyMaxPercent]) instead of the narrower lower bound.
 * That is intentional per ADR-001: the band documents real-world error, so the
 * conservative width is used symmetrically for display and persistence.
 *
 * Returns `null` when there is no usable estimate. Non-finite values are
 * rejected, [CalorieSummary.lowKcal] is clamped to zero and a band that still
 * overflows is dropped rather than persisted as `NaN`/`Infinity`.
 */
fun CalorieResult.toSummary(): CalorieSummary? =
    totalKcal?.takeIf { it.isFinite() && it >= MIN_KCAL }?.let { estimate ->
        val percent = uncertaintyMaxPercent.coerceAtLeast(0) / PERCENT_DIVISOR
        val low = (estimate * (ONE - percent)).coerceAtLeast(MIN_KCAL)
        val high = estimate * (ONE + percent)
        if (low.isFinite() && high.isFinite()) {
            CalorieSummary(
                estimateKcal = estimate,
                lowKcal = low,
                highKcal = high,
                model = model.name,
            )
        } else {
            null
        }
    }

private const val PERCENT_DIVISOR = 100.0
private const val ONE = 1.0
private const val MIN_KCAL = 0.0
