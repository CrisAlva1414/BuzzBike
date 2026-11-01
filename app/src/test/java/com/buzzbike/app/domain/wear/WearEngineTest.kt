package com.buzzbike.app.domain.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM puros del motor de desgaste (ADR-004:29-45, 56-63).
 *
 * No usan Android ni Room: `domain/` debe correr en JVM pura (AGENTS.md §8).
 */
class WearEngineTest {
    @Test
    fun given_usageOnKmAxis_when_assessed_then_ratioAndAxisComeFromKm() {
        val lifecycle = WearLifecycle(lifecycleKm = 2000.0)

        val assessment = WearEngine.assess(lifecycle, ComponentUsage(km = 1000.0, hours = 0.0, days = 0.0))

        assertRatio(0.5, assessment.ratio)
        assertEquals(WearStatus.OK, assessment.status)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_usageOnHoursAxis_when_assessed_then_ratioAndAxisComeFromHours() {
        val lifecycle = WearLifecycle(lifecycleHours = 100.0)

        val assessment = WearEngine.assess(lifecycle, ComponentUsage(km = 0.0, hours = 50.0, days = 0.0))

        assertRatio(0.5, assessment.ratio)
        assertEquals(WearStatus.OK, assessment.status)
        assertEquals(WearAxis.HOURS, assessment.limitingAxis)
    }

    @Test
    fun given_usageOnDaysAxis_when_assessed_then_ratioAndAxisComeFromDays() {
        val lifecycle = WearLifecycle(lifecycleDays = 60.0)

        val assessment = WearEngine.assess(lifecycle, ComponentUsage(km = 0.0, hours = 0.0, days = 30.0))

        assertRatio(0.5, assessment.ratio)
        assertEquals(WearStatus.OK, assessment.status)
        assertEquals(WearAxis.DAYS, assessment.limitingAxis)
    }

    @Test
    fun given_multipleAxes_when_assessed_then_largestRatioWins() {
        val lifecycle = WearLifecycle(lifecycleKm = 2000.0, lifecycleHours = 100.0)

        // km = 0.5, hours = 0.9 -> gana hours.
        val assessment = WearEngine.assess(lifecycle, ComponentUsage(km = 1000.0, hours = 90.0, days = 0.0))

        assertRatio(0.9, assessment.ratio)
        assertEquals(WearStatus.APPROACHING, assessment.status)
        assertEquals(WearAxis.HOURS, assessment.limitingAxis)
    }

    @Test
    fun given_tiedAxes_when_assessed_then_firstAxisInOrderWins() {
        val lifecycle = WearLifecycle(lifecycleKm = 1000.0, lifecycleHours = 100.0)

        // Ambos ratios son 0.5; el orden documentado es KM, HOURS, DAYS.
        val assessment = WearEngine.assess(lifecycle, ComponentUsage(km = 500.0, hours = 50.0, days = 0.0))

        assertRatio(0.5, assessment.ratio)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_ratioBelowApproachingThreshold_when_assessed_then_ok() {
        val assessment = assessKmRatio(799.0)

        assertRatio(0.799, assessment.ratio)
        assertEquals(WearStatus.OK, assessment.status)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_ratioExactlyAtApproachingThreshold_when_assessed_then_approaching() {
        val assessment = assessKmRatio(800.0)

        assertRatio(0.80, assessment.ratio)
        assertEquals(WearStatus.APPROACHING, assessment.status)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_ratioBetweenThresholds_when_assessed_then_approaching() {
        val assessment = assessKmRatio(999.0)

        assertRatio(0.999, assessment.ratio)
        assertEquals(WearStatus.APPROACHING, assessment.status)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_ratioExactlyAtDueThreshold_when_assessed_then_due() {
        val assessment = assessKmRatio(1000.0)

        assertRatio(1.0, assessment.ratio)
        assertEquals(WearStatus.DUE, assessment.status)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_ratioAboveDueThreshold_when_assessed_then_due() {
        val assessment = assessKmRatio(1010.0)

        assertRatio(1.01, assessment.ratio)
        assertEquals(WearStatus.DUE, assessment.status)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_nullLifecycleDays_when_assessed_then_daysAxisIsNotEvaluated() {
        val lifecycle = WearLifecycle(lifecycleKm = 1000.0)
        val usage = ComponentUsage(km = 100.0, hours = 0.0, days = 100_000.0)

        val assessment = WearEngine.assess(lifecycle, usage)

        assertRatio(0.1, assessment.ratio)
        assertEquals(WearStatus.OK, assessment.status)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_noLifecycle_when_assessed_then_okWithoutRatioOrAxis() {
        val assessment = WearEngine.assess(null, ComponentUsage(km = 100.0, hours = 100.0, days = 100.0))

        assertNull(assessment.ratio)
        assertEquals(WearStatus.OK, assessment.status)
        assertNull(assessment.limitingAxis)
    }

    @Test
    fun given_infiniteUsage_when_assessed_then_ratioIsSaturatedAndDue() {
        val lifecycle = WearLifecycle(lifecycleKm = 1000.0)

        val assessment =
            WearEngine.assess(
                lifecycle,
                ComponentUsage(km = Double.POSITIVE_INFINITY, hours = 0.0, days = 0.0),
            )

        assertRatio(Double.MAX_VALUE, assessment.ratio)
        assertTrue(assessment.ratio?.isFinite() == true)
        assertEquals(WearStatus.DUE, assessment.status)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_infiniteUsageAndInfiniteLifecycle_when_assessed_then_nanAxisIsIgnored() {
        val lifecycle = WearLifecycle(lifecycleKm = Double.POSITIVE_INFINITY)

        val assessment =
            WearEngine.assess(
                lifecycle,
                ComponentUsage(km = Double.POSITIVE_INFINITY, hours = 0.0, days = 0.0),
            )

        assertNull(assessment.ratio)
        assertEquals(WearStatus.OK, assessment.status)
        assertNull(assessment.limitingAxis)
    }

    @Test
    fun given_infiniteLifecycleAndFiniteUsage_when_assessed_then_ratioIsZero() {
        val lifecycle = WearLifecycle(lifecycleKm = Double.POSITIVE_INFINITY)

        val assessment =
            WearEngine.assess(
                lifecycle,
                ComponentUsage(km = 100.0, hours = 0.0, days = 0.0),
            )

        assertRatio(0.0, assessment.ratio)
        assertEquals(WearStatus.OK, assessment.status)
        assertEquals(WearAxis.KM, assessment.limitingAxis)
    }

    @Test
    fun given_allLifecycleAxesNull_when_constructed_then_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WearLifecycle()
        }
    }

    @Test
    fun given_nonPositiveLifecycleAxis_when_constructed_then_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WearLifecycle(lifecycleKm = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WearLifecycle(lifecycleHours = -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WearLifecycle(lifecycleDays = Double.NaN)
        }
    }

    @Test
    fun given_negativeOrNaNAccumulatedUsage_when_constructed_then_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ComponentUsage(km = -0.1, hours = 0.0, days = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComponentUsage(km = 0.0, hours = Double.NaN, days = 0.0)
        }
    }

    @Test
    fun given_chainDefault_when_resolved_then_usesTwoThousandKm() {
        val lifecycle = requireNotNull(ComponentDefaults.lifecycleFor(WearType.CHAIN))

        assertEquals(2000.0, requireNotNull(lifecycle.lifecycleKm), RATIO_TOLERANCE)
        assertNull(lifecycle.lifecycleHours)
        assertNull(lifecycle.lifecycleDays)
    }

    @Test
    fun given_suspensionDefault_when_resolved_then_usesOneHundredHours() {
        val lifecycle = requireNotNull(ComponentDefaults.lifecycleFor(WearType.SUSPENSION))

        assertNull(lifecycle.lifecycleKm)
        assertEquals(100.0, requireNotNull(lifecycle.lifecycleHours), RATIO_TOLERANCE)
        assertNull(lifecycle.lifecycleDays)
    }

    @Test
    fun given_tiresDefault_when_resolved_then_usesThreeThousandKm() {
        val lifecycle = requireNotNull(ComponentDefaults.lifecycleFor(WearType.TIRES))

        assertEquals(3000.0, requireNotNull(lifecycle.lifecycleKm), RATIO_TOLERANCE)
        assertNull(lifecycle.lifecycleHours)
        assertNull(lifecycle.lifecycleDays)
    }

    @Test
    fun given_brakesDefault_when_resolved_then_hasNoLifecycleAndNeedsConfiguration() {
        assertNull(ComponentDefaults.lifecycleFor(WearType.BRAKES))
        assertNull(WearEngine.assess(null, ComponentUsage(km = 10.0, hours = 0.0, days = 0.0)).ratio)
    }

    private fun assessKmRatio(usedKm: Double): WearAssessment {
        val lifecycle = WearLifecycle(lifecycleKm = 1000.0)
        return WearEngine.assess(lifecycle, ComponentUsage(km = usedKm, hours = 0.0, days = 0.0))
    }

    private fun assertRatio(
        expected: Double,
        actual: Double?,
    ) {
        val ratio = requireNotNull(actual)
        assertEquals(expected, ratio, RATIO_TOLERANCE)
    }

    private companion object {
        const val RATIO_TOLERANCE = 1e-12
    }
}
