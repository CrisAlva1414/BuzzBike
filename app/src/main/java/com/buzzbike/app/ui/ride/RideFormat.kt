package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.calories.CalorieModel
import com.buzzbike.app.domain.calories.CalorieSummary
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure, JVM-testable formatting of ride metrics.
 *
 * Domain values stay in metres, metres per second and seconds: this object only
 * renders them. It must never import `android.*` (it has to run in plain JVM
 * tests) and must never recalculate a domain formula.
 *
 * Unusable inputs (non-finite or negative, where a negative value is not
 * meaningful) degrade to [UNKNOWN] instead of leaking `NaN`/`∞` into the UI.
 */
object RideFormat {
    private const val METERS_PER_KM = 1000.0
    private const val MPS_TO_KPH = 3.6
    private const val MIN_METERS = 0.0
    private const val ZERO_SECONDS = 0L
    private const val MINUTE_SECONDS = 60L
    private const val HOUR_SECONDS = 3600L

    private const val KM_SUFFIX = " km"
    private const val KMH_SUFFIX = " km/h"
    private const val METER_SUFFIX = " m"
    private const val KCAL_SUFFIX = " kcal"
    private const val APPROX = "≈ "
    private const val RANGE_DASH = "–"
    private const val PROFILE_SEPARATOR = " · "
    private const val WITH_PROFILE = "con perfil"
    private const val WITHOUT_PROFILE = "sin perfil"

    /** Placeholder shown when a value cannot be formatted. */
    const val UNKNOWN = "—"

    /**
     * Formats a distance in metres as kilometres with two decimals.
     *
     * `0.0` -> `"0,00 km"`, `12345.6` -> `"12,35 km"` (locale-dependent
     * decimal separator).
     */
    fun distanceKm(
        meters: Double,
        locale: Locale = Locale.getDefault(),
    ): String =
        finiteNonNegative(meters)?.let {
            String.format(locale, "%.2f", it / METERS_PER_KM) + KM_SUFFIX
        } ?: UNKNOWN

    /**
     * Formats a speed in metres per second as kilometres per hour with one
     * decimal. Non-finite or negative input degrades to [UNKNOWN].
     */
    fun speedKmh(
        mps: Double,
        locale: Locale = Locale.getDefault(),
    ): String =
        finiteNonNegative(mps)?.let {
            String.format(locale, "%.1f", it * MPS_TO_KPH) + KMH_SUFFIX
        } ?: UNKNOWN

    /**
     * Formats a duration in seconds as `M:SS`, or `H:MM:SS` when it reaches an
     * hour. Negative input is clamped to `0:00`.
     */
    fun duration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(ZERO_SECONDS)
        val hours = safe / HOUR_SECONDS
        val minutes = safe % HOUR_SECONDS / MINUTE_SECONDS
        val secs = safe % MINUTE_SECONDS
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, secs)
        }
    }

    /**
     * Formats an elevation gain in metres as a whole number. A gain cannot be
     * negative, so non-finite or negative input degrades to [UNKNOWN].
     */
    fun elevationM(
        meters: Double,
        locale: Locale = Locale.getDefault(),
    ): String =
        finiteNonNegative(meters)?.let {
            wholeNumber(it, locale) + METER_SUFFIX
        } ?: UNKNOWN

    /**
     * Formats an altitude in metres as a whole number.
     *
     * `null` and non-finite values degrade to [UNKNOWN]. A negative altitude is
     * physically valid (below sea level), so it is rendered as-is.
     */
    fun altitudeM(
        meters: Double?,
        locale: Locale = Locale.getDefault(),
    ): String =
        meters?.takeIf { it.isFinite() }?.let {
            wholeNumber(it, locale) + METER_SUFFIX
        } ?: UNKNOWN

    /**
     * Renders an already-computed calorie estimate as a product band, never as
     * a single exact number (ADR-001, AGENTS.md §6.4).
     *
     * The low bound is rounded down and the high bound up so the displayed
     * range never becomes narrower than the calculated one. `null`, non-finite
     * or inverted bands return `null`; the UI shows [UNKNOWN] instead of
     * inventing a value.
     */
    fun caloriesBand(
        calories: CalorieSummary?,
        hasProfile: Boolean,
        locale: Locale = Locale.getDefault(),
    ): CalorieBandText? =
        calories
            ?.takeIf { it.lowKcal.isFinite() && it.highKcal.isFinite() && it.lowKcal <= it.highKcal }
            ?.let { buildBand(it, hasProfile, locale) }

    private fun buildBand(
        calories: CalorieSummary,
        hasProfile: Boolean,
        locale: Locale,
    ): CalorieBandText {
        val lowWhole = floor(calories.lowKcal.coerceAtLeast(MIN_METERS))
        val highWhole = ceil(calories.highKcal.coerceAtLeast(MIN_METERS))
        val value =
            APPROX + wholeNumber(lowWhole, locale) + RANGE_DASH + wholeNumber(highWhole, locale) + KCAL_SUFFIX
        val profile = if (hasProfile) WITH_PROFILE else WITHOUT_PROFILE
        return CalorieBandText(
            value = value,
            model = modelLabel(calories.model) + PROFILE_SEPARATOR + profile,
        )
    }

    private fun finiteNonNegative(value: Double): Double? = value.takeIf { it.isFinite() && it >= MIN_METERS }

    private fun wholeNumber(
        value: Double,
        locale: Locale,
    ): String = String.format(locale, "%,.0f", value)

    private fun modelLabel(model: String): String =
        when (model) {
            CalorieModel.COMPENDIUM_VELOCITY.name -> "Compendium por velocidad"
            CalorieModel.KEYTEL_HR.name -> "Keytel (FC)"
            CalorieModel.ACSM_POWER.name -> "ACSM (potencia)"
            CalorieModel.NONE.name -> "sin modelo"
            else -> model
        }
}

/**
 * Two lines describing a calorie estimate: the mandatory band ([value]) and the
 * model plus profile context ([model]).
 */
data class CalorieBandText(
    val value: String,
    val model: String,
)
