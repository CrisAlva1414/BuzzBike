package com.buzzbike.app.ui.history

import com.buzzbike.app.domain.calories.CalorieSummary
import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.history.RideRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** JVM-pure tests for the calorie context rendered on the ride detail screen. */
class RideDetailFormatTest {
    private val esCl: Locale = Locale.forLanguageTag("es-CL")

    @Test
    fun given_completeSnapshot_when_detailCells_thenCaloriesUseConPerfil() {
        val ride =
            ride(
                profile =
                    UserProfile(
                        weightKg = 72.5,
                        heightCm = 178.0,
                        ageYears = 34,
                        isMale = true,
                    ),
            )

        val calories = caloriesCell(ride)

        assertTrue(calories.label.contains("con perfil"))
        assertFalse(calories.label.contains("sin perfil"))
    }

    @Test
    fun given_partialSnapshot_when_detailCells_thenCaloriesUseSinPerfil() {
        val ride = ride(profile = UserProfile(weightKg = 72.5))

        val calories = caloriesCell(ride)

        assertTrue(calories.label.contains("sin perfil"))
        assertFalse(calories.label.contains("con perfil"))
    }

    @Test
    fun given_noSnapshot_when_detailCells_thenCaloriesUseSinPerfilAndShowAssumption() {
        val ride = ride(profile = null)

        val calories = caloriesCell(ride)

        assertTrue(calories.label.contains("sin perfil"))
        assertTrue(calories.label.contains("supuesto: 70 kg"))
    }

    private fun caloriesCell(ride: RideRecord) =
        detailMetricCells(ride, esCl).single { it.label.startsWith("Calorías") }

    private fun ride(profile: UserProfile?): RideRecord =
        RideRecord(
            id = "ride-1",
            startTimeMillis = 1_700_000_000_000L,
            endTimeMillis = 1_700_000_600_000L,
            distanceM = 12_345.0,
            movingTimeSeconds = 2_700L,
            stoppedTimeSeconds = 0L,
            elevationGainM = 100.0,
            elevationLossM = 80.0,
            avgSpeedMps = 5.0,
            maxSpeedMps = 9.0,
            calories =
                CalorieSummary(
                    estimateKcal = 400.0,
                    lowKcal = 310.0,
                    highKcal = 560.0,
                    model = "COMPENDIUM_VELOCITY",
                    assumedWeightKg = if (profile?.weightKg == null) 70.0 else null,
                ),
            profileSnapshot = profile,
        )
}
