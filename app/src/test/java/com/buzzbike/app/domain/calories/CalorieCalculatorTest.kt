package com.buzzbike.app.domain.calories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun given_completeProfile_when_stop_thenUsesMifflinReeBaseline() {
        val result =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(0.0),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(false),
                profile = UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30, isMale = true),
            )

        assertTrue(result.hasProfile)
        // ADR-001 §4: stop baseline becomes Mifflin REE / 1440, not 1.0 MET.
        // REE = 1648.75 kcal/day -> 1648.75 / 1440 kcal/min for one minute.
        assertEquals(1648.75 / 1440.0, result.totalKcal ?: Double.NaN, 1e-9)
        assertEquals(0.0, result.activeKcal ?: Double.NaN, 1e-9)
    }

    @Test
    fun given_partialProfile_when_stop_thenKeepsOneMetBaseline() {
        val result =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(0.0),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(false),
                // Missing sex: not a complete profile, so no Mifflin REE baseline.
                profile = UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30),
            )

        assertFalse(result.hasProfile)
        assertEquals(1.225, result.totalKcal ?: Double.NaN, 0.01)
    }

    @Test
    fun given_completeProfile_when_moving_thenCascadeIsUnchanged() {
        val withCompleteProfile =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(6.7), // ~15 mph
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30, isMale = true),
            )
        val withoutCompleteProfile =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(6.7),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(weightKg = 70.0),
            )

        // The REE baseline only replaces the stop MET; moving samples keep the
        // Compendium band, so kcal and active kcal are identical for both.
        assertEquals(withoutCompleteProfile.totalKcal ?: Double.NaN, withCompleteProfile.totalKcal ?: Double.NaN, 1e-9)
        assertEquals(
            withoutCompleteProfile.activeKcal ?: Double.NaN,
            withCompleteProfile.activeKcal ?: Double.NaN,
            1e-9,
        )
    }

    @Test
    fun given_emptyProfile_when_missingInputs_thenListsAllFour() {
        val result =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(6.7),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(),
            )

        assertEquals(listOf("weight", "height", "age", "sex"), result.missingInputs)
    }

    @Test
    fun given_completeProfile_when_missingInputs_thenEmpty() {
        val result =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(6.7),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30, isMale = true),
            )

        assertTrue(result.hasProfile)
        assertEquals(emptyList<String>(), result.missingInputs)
    }

    @Test
    fun given_partialProfile_when_missingInputs_thenListsOnlyAbsent() {
        val result =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(6.7),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(weightKg = 70.0, heightCm = 175.0, isMale = true),
            )

        assertEquals(listOf("age"), result.missingInputs)
    }

    @Test
    fun given_nonPositiveValues_when_missingInputs_thenCountedAsMissing() {
        val result =
            CalorieCalculator().calculate(
                speedSamplesMps = listOf(6.7),
                durationsSeconds = listOf(60.0),
                isMoving = listOf(true),
                profile = UserProfile(weightKg = 0.0, heightCm = -1.0, ageYears = 0, isMale = true),
            )

        assertEquals(listOf("weight", "height", "age"), result.missingInputs)
    }
}
