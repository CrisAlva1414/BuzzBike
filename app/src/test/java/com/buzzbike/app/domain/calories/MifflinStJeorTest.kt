package com.buzzbike.app.domain.calories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot tests for the Mifflin-St Jeor REE equation (ADR-001 §5.1).
 *
 * Expected values were computed by hand from the published equation:
 * Mifflin et al. 1990, DOI 10.1093/ajcn/51.2.241.
 */
class MifflinStJeorTest {
    @Test
    fun given_completeMaleProfile_thenReturnsKnownRee() {
        val profile = UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30, isMale = true)

        // 10*70 + 6.25*175 - 5*30 + 5 = 1648.75 kcal/day
        assertEquals(1648.75, restingEnergyExpenditureKcalPerDay(profile) ?: Double.NaN, 1e-9)
    }

    @Test
    fun given_completeFemaleProfile_thenReturnsKnownRee() {
        val profile = UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30, isMale = false)

        // 10*70 + 6.25*175 - 5*30 - 161 = 1482.75 kcal/day
        assertEquals(1482.75, restingEnergyExpenditureKcalPerDay(profile) ?: Double.NaN, 1e-9)
    }

    @Test
    fun given_completeProfile_thenRestingKcalPerMinuteIsReeOver1440() {
        val profile = UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30, isMale = true)

        // 1648.75 / 1440 = 1.144965277... kcal/min
        assertEquals(1648.75 / 1440.0, restingKcalPerMinute(profile) ?: Double.NaN, 1e-9)
    }

    @Test
    fun given_missingAnyRequiredInput_thenReeIsNull() {
        val incompleteProfiles =
            listOf(
                UserProfile(heightCm = 175.0, ageYears = 30, isMale = true),
                UserProfile(weightKg = 70.0, ageYears = 30, isMale = true),
                UserProfile(weightKg = 70.0, heightCm = 175.0, isMale = true),
                UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30),
                UserProfile(weightKg = 0.0, heightCm = 175.0, ageYears = 30, isMale = true),
                UserProfile(weightKg = 70.0, heightCm = 0.0, ageYears = 30, isMale = true),
                UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 0, isMale = true),
            )

        incompleteProfiles.forEach { profile ->
            assertFalse("expected incomplete: $profile", profile.isComplete)
            assertNull(restingEnergyExpenditureKcalPerDay(profile))
            assertNull(restingKcalPerMinute(profile))
        }
    }

    @Test
    fun given_completeProfile_thenIsCompleteTrue() {
        val profile = UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30, isMale = false)

        assertTrue(profile.isComplete)
    }
}
