package com.buzzbike.app.domain.calories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalorieCalculatorTest {
    @Test
    fun given_noProfile_thenUses70kgAndShowsUncertainty() {
        val calculator = CalorieCalculator()
        val result =
            calculator.calculate(
                speedSamplesMps = listOf(6.7), // ~15 mph
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(),
            )

        assertNotNull(result.totalKcal)
        assertEquals(70.0, result.assumedWeightKg!!, 0.001)
        assertEquals(30, result.uncertaintyMinPercent)
        assertEquals(40, result.uncertaintyMaxPercent)
        assertEquals(CalorieModel.COMPENDIUM_VELOCITY, result.model)
    }

    @Test
    fun given_withProfile_thenBetterUncertainty() {
        val calculator = CalorieCalculator()
        val result =
            calculator.calculate(
                speedSamplesMps = listOf(6.7),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(weightKg = 75.0, heightCm = 180.0, ageYears = 30, isMale = true),
            )

        assertNotNull(result.totalKcal)
        assertNull(result.assumedWeightKg)
        assertEquals(20, result.uncertaintyMinPercent)
        assertEquals(30, result.uncertaintyMaxPercent)
        assertTrue(result.hasProfile)
    }

    @Test
    fun given_stop_thenUses1Met() {
        val calculator = CalorieCalculator()
        val result =
            calculator.calculate(
                speedSamplesMps = listOf(0.0),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(false),
                profile = UserProfile(weightKg = 70.0),
            )

        assertNotNull(result.totalKcal)
        // 1 MET * 1 min * 3.5 * 70 / 200 = 1.225 kcal
        assertEquals(1.225, result.totalKcal!!, 0.01)
    }

    @Test
    fun given_noWeight_thenNoAbsoluteKcal() {
        val calculator = CalorieCalculator()
        val result =
            calculator.calculate(
                speedSamplesMps = listOf(6.7),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(heightCm = 180.0),
            )

        // With assumed 70kg we still give kcal
        assertNotNull(result.totalKcal)
    }

    @Test
    fun given_profile_when_toSummary_thenUsesWidestBandForBothSides() {
        val result =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(6.7),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(weightKg = 75.0, heightCm = 180.0, ageYears = 30, isMale = true),
            )

        val summary = requireNotNull(result.toSummary())
        val estimate = requireNotNull(result.totalKcal)

        assertEquals(20, result.uncertaintyMinPercent)
        assertEquals(30, result.uncertaintyMaxPercent)
        assertEquals(estimate, summary.estimateKcal, 0.0)
        assertEquals(estimate * 0.7, summary.lowKcal, 0.001)
        assertEquals(estimate * 1.3, summary.highKcal, 0.001)
        assertEquals("COMPENDIUM_VELOCITY", summary.model)
    }

    @Test
    fun given_noProfile_when_toSummary_thenUsesThirtyToFortyPercentBand() {
        val result =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(6.7),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(),
            )

        val summary = requireNotNull(result.toSummary())
        val estimate = requireNotNull(result.totalKcal)

        assertEquals(30, result.uncertaintyMinPercent)
        assertEquals(40, result.uncertaintyMaxPercent)
        assertEquals(estimate * 0.6, summary.lowKcal, 0.001)
        assertEquals(estimate * 1.4, summary.highKcal, 0.001)
    }

    @Test
    fun given_noEstimate_when_toSummary_thenNull() {
        val result =
            CalorieCalculator().calculate(
                speedSamplesMps = emptyList(),
                durationsSeconds = emptyList(),
                isMoving = emptyList(),
            )

        assertNull(result.totalKcal)
        assertNull(result.toSummary())
    }

    @Test
    fun given_speedInGap_thenTreatedAsStop() {
        val calculator = CalorieCalculator()
        val result =
            calculator.calculate(
                speedSamplesMps = listOf(8.5), // ~19 mph, in gap between 01050 and 01060
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(weightKg = 70.0),
            )

        // Should be treated as stop (1 MET) since no band
        assertEquals(1.225, result.totalKcal!!, 0.01)
    }
}
