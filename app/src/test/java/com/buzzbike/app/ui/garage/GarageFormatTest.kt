package com.buzzbike.app.ui.garage

import com.buzzbike.app.domain.wear.BikeComponent
import com.buzzbike.app.domain.wear.ComponentUsage
import com.buzzbike.app.domain.wear.ComponentWear
import com.buzzbike.app.domain.wear.WearAssessment
import com.buzzbike.app.domain.wear.WearAxis
import com.buzzbike.app.domain.wear.WearEngine
import com.buzzbike.app.domain.wear.WearLifecycle
import com.buzzbike.app.domain.wear.WearStatus
import com.buzzbike.app.domain.wear.WearType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * JVM tests for the garage formatting helpers.
 *
 * The percentage tests pin the 80% and 100% boundaries from ADR-004 and the
 * "never hide the number" rule: the displayed percentage must not collapse to
 * an exact-looking value or lose the over-100 case.
 */
class GarageFormatTest {
    @Test
    fun given_ratioBoundaries_when_percent_then_valuesAreExact() {
        assertEquals("0%", GarageFormat.percent(0.0, Locale.US))
        assertEquals("79.9%", GarageFormat.percent(0.799, Locale.US))
        assertEquals("80%", GarageFormat.percent(0.8, Locale.US))
        assertEquals("99.9%", GarageFormat.percent(0.999, Locale.US))
        assertEquals("100%", GarageFormat.percent(1.0, Locale.US))
        assertEquals("120%", GarageFormat.percent(1.2, Locale.US))
    }

    @Test
    fun given_largeOrMissingRatio_when_percent_thenItDegradesSafely() {
        assertEquals(">999%", GarageFormat.percent(20.0, Locale.US))
        assertEquals("—", GarageFormat.percent(null, Locale.US))
        assertEquals("—", GarageFormat.percent(Double.NaN, Locale.US))
        assertEquals("—", GarageFormat.percent(-1.0, Locale.US))
    }

    @Test
    fun given_spanishLocale_when_percent_thenDecimalSeparatorIsComma() {
        assertEquals("79,9%", GarageFormat.percent(0.799, Locale.forLanguageTag("es")))
    }

    @Test
    fun given_assessment_when_statusText_thenThresholdsMatchWearEngine() {
        assertEquals("OK", GarageFormat.statusText(assessment(0.79)))
        assertEquals("Próximo al cambio", GarageFormat.statusText(assessment(0.8)))
        assertEquals("Cambio vencido", GarageFormat.statusText(assessment(1.0)))
        assertEquals("Sin ciclo de vida", GarageFormat.statusText(WearAssessment(null, WearStatus.OK, null)))
    }

    @Test
    fun given_eachType_when_typeText_thenSpanishLabel() {
        assertEquals("Cadena", GarageFormat.typeText(WearType.CHAIN))
        assertEquals("Suspensión", GarageFormat.typeText(WearType.SUSPENSION))
        assertEquals("Neumáticos", GarageFormat.typeText(WearType.TIRES))
        assertEquals("Frenos", GarageFormat.typeText(WearType.BRAKES))
    }

    @Test
    fun given_kmLimitedComponent_when_limitingDetail_thenShowsAxisUsageAndLimit() {
        val wear = wear(lifecycle = WearLifecycle(lifecycleKm = 2000.0), usage = ComponentUsage(1590.4, 0.0, 0.0))

        assertEquals(
            "Eje limitante: km · 1,590.4 de 2,000 km",
            GarageFormat.limitingDetail(wear, Locale.US),
        )
    }

    @Test
    fun given_hoursLimitedComponent_when_limitingDetail_thenShowsHours() {
        val wear = wear(lifecycle = WearLifecycle(lifecycleHours = 100.0), usage = ComponentUsage(0.0, 26.5, 0.0))

        assertEquals(
            "Eje limitante: horas · 26.5 de 100 h",
            GarageFormat.limitingDetail(wear, Locale.US),
        )
    }

    @Test
    fun given_daysLimitedComponent_when_limitingDetail_thenShowsDays() {
        val lifecycle = WearLifecycle(lifecycleDays = 180.0)
        val wear = wear(lifecycle = lifecycle, usage = ComponentUsage(0.0, 0.0, 12.0))

        assertEquals(
            "Eje limitante: días · 12 de 180 días",
            GarageFormat.limitingDetail(wear, Locale.US),
        )
    }

    @Test
    fun given_componentWithoutLifecycle_when_limitingDetail_thenAsksToConfigureIt() {
        val wear = wear(lifecycle = null, usage = ComponentUsage(0.0, 0.0, 0.0))

        assertEquals("Sin ciclo de vida configurado.", GarageFormat.limitingDetail(wear, Locale.US))
    }

    @Test
    fun given_usage_when_usageDetail_thenShowsAllThreeAxes() {
        val wear = wear(lifecycle = null, usage = ComponentUsage(12.4, 1.5, 3.0))

        assertEquals(
            "Acumulado: 12.4 km · 1.5 h · 3 días",
            GarageFormat.usageDetail(wear.usage, Locale.US),
        )
    }

    private fun assessment(ratio: Double): WearAssessment =
        WearAssessment(
            ratio = ratio,
            status =
                when {
                    ratio >= WearEngine.DUE_THRESHOLD -> WearStatus.DUE
                    ratio >= WearEngine.APPROACHING_THRESHOLD -> WearStatus.APPROACHING
                    else -> WearStatus.OK
                },
            limitingAxis = WearAxis.KM,
        )

    private fun wear(
        lifecycle: WearLifecycle?,
        usage: ComponentUsage,
    ): ComponentWear {
        val component =
            BikeComponent(
                id = "component",
                bikeId = "bike",
                type = WearType.CHAIN,
                label = "Cadena",
                installedAtMillis = 0,
                lifecycle = lifecycle,
                retiredAtMillis = null,
                notes = null,
            )
        return ComponentWear(
            component = component,
            usage = usage,
            assessment = WearEngine.assess(lifecycle, usage),
            baselineMillis = 0,
        )
    }
}
