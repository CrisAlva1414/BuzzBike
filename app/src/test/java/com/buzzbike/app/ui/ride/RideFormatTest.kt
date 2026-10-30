package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.calories.CalorieModel
import com.buzzbike.app.domain.calories.CalorieSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RideFormatTest {
    private val esCl: Locale = Locale.forLanguageTag("es-CL")

    @Test
    fun distanceKm_formatsTwoDecimalsWithLocaleSeparator() {
        assertEquals("0.00 km", RideFormat.distanceKm(0.0, Locale.US))
        assertEquals("0,00 km", RideFormat.distanceKm(0.0, esCl))
        assertEquals("1.00 km", RideFormat.distanceKm(999.5, Locale.US))
        assertEquals("1,00 km", RideFormat.distanceKm(999.5, esCl))
        assertEquals("12.35 km", RideFormat.distanceKm(12345.6, Locale.US))
        assertEquals("12,35 km", RideFormat.distanceKm(12345.6, esCl))
    }

    @Test
    fun speedKmh_formatsOneDecimalAndDegradesUnusableValues() {
        assertEquals("0,0 km/h", RideFormat.speedKmh(0.0, esCl))
        // 1 m/s * 3.6 = 3.6 km/h
        assertEquals("3,6 km/h", RideFormat.speedKmh(1.0, esCl))
        assertEquals("13,0 km/h", RideFormat.speedKmh(3.6, esCl))
        assertEquals("—", RideFormat.speedKmh(Double.NaN, esCl))
        assertEquals("—", RideFormat.speedKmh(Double.POSITIVE_INFINITY, esCl))
        assertEquals("—", RideFormat.speedKmh(-1.0, esCl))
    }

    @Test
    fun duration_formatsMinutesAndHoursAndClampsNegative() {
        assertEquals("0:00", RideFormat.duration(0))
        assertEquals("0:59", RideFormat.duration(59))
        assertEquals("1:00", RideFormat.duration(60))
        assertEquals("59:59", RideFormat.duration(3599))
        assertEquals("1:00:00", RideFormat.duration(3600))
        assertEquals("1:01:01", RideFormat.duration(3661))
        assertEquals("0:00", RideFormat.duration(-5))
    }

    @Test
    fun elevationAndAltitude_formatWholeMetresAndDegradeUnusableValues() {
        assertEquals("0 m", RideFormat.elevationM(0.0, esCl))
        assertEquals("1.240 m", RideFormat.elevationM(1240.0, esCl))
        assertEquals("1,240 m", RideFormat.elevationM(1240.0, Locale.US))
        assertEquals("—", RideFormat.elevationM(-5.0, esCl))
        assertEquals("—", RideFormat.elevationM(Double.NaN, esCl))

        assertEquals("—", RideFormat.altitudeM(null, esCl))
        assertEquals("—", RideFormat.altitudeM(Double.NaN, esCl))
        assertEquals("—", RideFormat.altitudeM(Double.POSITIVE_INFINITY, esCl))
        // Negative altitude is valid (below sea level) and rendered as-is.
        assertEquals("-12 m", RideFormat.altitudeM(-12.0, esCl))
        assertEquals("1.240 m", RideFormat.altitudeM(1240.0, esCl))
    }

    @Test
    fun caloriesBand_buildsRangeModelAndProfile() {
        val withoutProfile = RideFormat.caloriesBand(summary(310.4, 559.2), hasProfile = false, locale = esCl)
        assertNotNull(withoutProfile)
        assertEquals("≈ 310–560 kcal", withoutProfile?.value)
        assertEquals("Compendium por velocidad · sin perfil", withoutProfile?.model)

        val withProfile = RideFormat.caloriesBand(summary(310.4, 559.2), hasProfile = true, locale = esCl)
        assertEquals("Compendium por velocidad · con perfil", withProfile?.model)

        // low floors, high ceils: the band never narrows.
        val rounded = RideFormat.caloriesBand(summary(310.9, 311.1), hasProfile = false, locale = esCl)
        assertEquals("≈ 310–312 kcal", rounded?.value)

        val keytel = RideFormat.caloriesBand(summary(100.0, 200.0, CalorieModel.KEYTEL_HR.name), false, esCl)
        assertEquals("Keytel (FC) · sin perfil", keytel?.model)
        val acsm = RideFormat.caloriesBand(summary(100.0, 200.0, CalorieModel.ACSM_POWER.name), false, esCl)
        assertEquals("ACSM (potencia) · sin perfil", acsm?.model)
        val none = RideFormat.caloriesBand(summary(100.0, 200.0, CalorieModel.NONE.name), false, esCl)
        assertEquals("sin modelo · sin perfil", none?.model)
    }

    @Test
    fun caloriesBand_returnsNullForMissingOrCorruptInput() {
        assertNull(RideFormat.caloriesBand(null, hasProfile = false, locale = esCl))
        // Inverted/corrupt bands are rejected instead of silently swapped.
        assertNull(RideFormat.caloriesBand(summary(600.0, 300.0), hasProfile = false, locale = esCl))
        assertNull(RideFormat.caloriesBand(summary(Double.NaN, 300.0), hasProfile = false, locale = esCl))
        assertNull(RideFormat.caloriesBand(summary(100.0, Double.POSITIVE_INFINITY), false, esCl))
    }

    @Test
    fun caloriesBand_neverShowsKcalWithoutRange() {
        val band = RideFormat.caloriesBand(summary(310.0, 560.0), hasProfile = true, locale = esCl)
        assertNotNull(band)
        val value = band?.value.orEmpty()
        assertTrue("band must contain kcal: $value", value.contains("kcal"))
        val beforeUnit = value.substringBefore("kcal")
        assertTrue("kcal must always be accompanied by an en dash: $value", beforeUnit.contains("–"))
    }

    @Test
    fun caloriesBand_preservesUnknownModelName() {
        val band = RideFormat.caloriesBand(summary(100.0, 200.0, "MY_CUSTOM_MODEL"), false, esCl)
        assertEquals("MY_CUSTOM_MODEL · sin perfil", band?.model)
    }

    private fun summary(
        low: Double,
        high: Double,
        model: String = CalorieModel.COMPENDIUM_VELOCITY.name,
    ): CalorieSummary =
        CalorieSummary(
            estimateKcal = 400.0,
            lowKcal = low,
            highKcal = high,
            model = model,
        )
}
