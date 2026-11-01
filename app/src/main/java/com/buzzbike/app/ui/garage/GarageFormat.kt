package com.buzzbike.app.ui.garage

import com.buzzbike.app.domain.wear.ComponentUsage
import com.buzzbike.app.domain.wear.ComponentWear
import com.buzzbike.app.domain.wear.WearAssessment
import com.buzzbike.app.domain.wear.WearAxis
import com.buzzbike.app.domain.wear.WearStatus
import com.buzzbike.app.domain.wear.WearType
import java.util.Locale
import kotlin.math.floor

private const val PERCENT_FACTOR = 100.0
private const val MAX_DISPLAY_PERCENT = 999.0
private const val UNKNOWN = "—"
private const val PERCENT_SUFFIX = "%"
private const val NO_LIFECYCLE_TEXT = "Sin ciclo de vida configurado."
private const val KM_SUFFIX = " km"
private const val HOURS_SUFFIX = " h"
private const val DAYS_SUFFIX = " días"
private const val LIMITING_PREFIX = "Eje limitante: "
private const val USAGE_PREFIX = "Acumulado: "
private const val SEPARATOR = " · "
private const val DE_SEPARATOR = " de "

/**
 * Pure, JVM-testable formatting for the garage.
 *
 * It renders already-computed domain values and never recalculates wear: the
 * ratio, status and limiting axis come straight from [WearAssessment]. Keeping
 * this object free of `android.*` lets the percentage/status thresholds be
 * tested on the JVM, including the 80% and 100% boundaries from ADR-004.
 */
object GarageFormat {
    /**
     * Renders a wear ratio as a percentage.
     *
     * `0.0` -> `"0%"`, `0.799` -> `"79.9%"`, `0.8` -> `"80%"`,
     * `0.999` -> `"99.9%"`, `1.0` -> `"100%"`, `1.2` -> `"120%"`.
     * A missing or non-finite ratio degrades to [UNKNOWN].
     */
    fun percent(
        ratio: Double?,
        locale: Locale = Locale.ROOT,
    ): String {
        val percentage =
            ratio
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.times(PERCENT_FACTOR)
        return when {
            percentage == null -> UNKNOWN
            percentage > MAX_DISPLAY_PERCENT -> ">${MAX_DISPLAY_PERCENT.toInt()}%"
            else -> {
                val formatted = String.format(locale, "%.1f", percentage)
                formatted.trimEnd('0').trimEnd('.', ',') + PERCENT_SUFFIX
            }
        }
    }

    /** Human label of the wear state; a component without lifecycle never alerts. */
    fun statusText(assessment: WearAssessment): String =
        if (assessment.ratio == null) {
            "Sin ciclo de vida"
        } else {
            alertText(assessment.status)
        }

    /** Human label of a wear status, used by cards and bike indicators. */
    fun alertText(status: WearStatus): String =
        when (status) {
            WearStatus.OK -> "OK"
            WearStatus.APPROACHING -> "Próximo al cambio"
            WearStatus.DUE -> "Cambio vencido"
        }

    /** Spanish name of a component type. */
    fun typeText(type: WearType): String =
        when (type) {
            WearType.CHAIN -> "Cadena"
            WearType.SUSPENSION -> "Suspensión"
            WearType.TIRES -> "Neumáticos"
            WearType.BRAKES -> "Frenos"
        }

    /** Short label of the axis that drives the current wear ratio. */
    fun axisText(axis: WearAxis): String =
        when (axis) {
            WearAxis.KM -> "km"
            WearAxis.HOURS -> "horas"
            WearAxis.DAYS -> "días"
        }

    /**
     * Detail of the limiting axis: `"Eje limitante: km · 1.590,4 de 2.000 km"`.
     *
     * Without a configured lifecycle there is no axis to render, so the card
     * asks the user to configure one instead of inventing a default.
     */
    fun limitingDetail(
        wear: ComponentWear,
        locale: Locale = Locale.ROOT,
    ): String {
        val axis = wear.assessment.limitingAxis
        val lifecycle = wear.component.lifecycle
        return if (axis == null || lifecycle == null) {
            NO_LIFECYCLE_TEXT
        } else {
            val (used, limit, suffix) =
                when (axis) {
                    WearAxis.KM ->
                        Triple(wear.usage.km, lifecycle.lifecycleKm, KM_SUFFIX)
                    WearAxis.HOURS ->
                        Triple(wear.usage.hours, lifecycle.lifecycleHours, HOURS_SUFFIX)
                    WearAxis.DAYS ->
                        Triple(wear.usage.days, lifecycle.lifecycleDays, DAYS_SUFFIX)
                }
            limit?.let { limitValue ->
                LIMITING_PREFIX +
                    axisText(axis) +
                    SEPARATOR +
                    decimal(used, locale) +
                    DE_SEPARATOR +
                    decimal(limitValue, locale) +
                    suffix
            } ?: NO_LIFECYCLE_TEXT
        }
    }

    /** Editable text prefill for a numeric field, without grouping or trailing zeros. */
    fun inputDecimal(value: Double): String =
        if (!value.isFinite() || value < 0.0) {
            "0"
        } else {
            String.format(Locale.ROOT, "%.1f", value).trimEnd('0').trimEnd('.')
        }

    /** Accumulated usage on all three axes: `"Acumulado: 12,4 km · 1,5 h · 3 días"`. */
    fun usageDetail(
        usage: ComponentUsage,
        locale: Locale = Locale.ROOT,
    ): String =
        USAGE_PREFIX +
            decimal(usage.km, locale) +
            KM_SUFFIX +
            SEPARATOR +
            decimal(usage.hours, locale) +
            HOURS_SUFFIX +
            SEPARATOR +
            decimal(usage.days, locale) +
            DAYS_SUFFIX

    private fun decimal(
        value: Double,
        locale: Locale,
    ): String =
        if (value == floor(value)) {
            String.format(locale, "%,.0f", value)
        } else {
            String.format(locale, "%,.1f", value)
        }
}
