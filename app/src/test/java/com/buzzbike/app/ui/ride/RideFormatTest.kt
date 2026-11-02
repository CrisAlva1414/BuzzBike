package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.calories.CalorieModel
import com.buzzbike.app.domain.calories.CalorieSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun caloriesBand_labelsAssumedWeightWhenNoProfileWeightWasKnown() {
        val withAssumption =
            RideFormat.caloriesBand(
                summary(310.0, 560.0).copy(assumedWeightKg = 70.0),
                hasProfile = false,
                locale = esCl,
            )

        assertEquals("Compendium por velocidad · sin perfil · supuesto: 70 kg", withAssumption?.model)

        val withKnownWeight =
            RideFormat.caloriesBand(
                summary(310.0, 560.0).copy(assumedWeightKg = null),
                hasProfile = false,
                locale = esCl,
            )
        assertEquals("Compendium por velocidad · sin perfil", withKnownWeight?.model)
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

    @Test
    fun unitSuffixes_areInvariantAcrossLocales() {
        // Only the decimal separator may change: the unit text itself must be
        // identical so TalkBack and screenshots do not depend on the locale.
        val distanceUs = RideFormat.distanceKm(12345.6, Locale.US)
        val distanceCl = RideFormat.distanceKm(12345.6, esCl)
        assertEquals(" km", distanceUs.takeLast(3))
        assertEquals(distanceUs.replace(',', '.'), distanceCl.replace(',', '.'))

        val speedUs = RideFormat.speedKmh(3.6, Locale.US)
        val speedCl = RideFormat.speedKmh(3.6, esCl)
        assertEquals(" km/h", speedUs.takeLast(5))
        assertEquals(speedUs.replace(',', '.'), speedCl.replace(',', '.'))

        val elevationUs = RideFormat.elevationM(240.0, Locale.US)
        val elevationCl = RideFormat.elevationM(240.0, esCl)
        assertEquals(" m", elevationUs.takeLast(2))
        assertEquals(elevationUs, elevationCl)

        val bandUs = RideFormat.caloriesBand(summary(310.4, 559.2), false, Locale.US)
        val bandCl = RideFormat.caloriesBand(summary(310.4, 559.2), false, esCl)
        assertNotNull(bandUs)
        assertNotNull(bandCl)
        assertEquals(" kcal", bandUs?.value?.takeLast(5))
        assertEquals(bandUs?.value, bandCl?.value)
    }

    @Test
    fun distanceKm_degradesNegativeAndNonFiniteValues() {
        assertEquals("—", RideFormat.distanceKm(-0.1, esCl))
        assertEquals("—", RideFormat.distanceKm(Double.NaN, esCl))
        assertEquals("—", RideFormat.distanceKm(Double.POSITIVE_INFINITY, esCl))
        assertEquals("—", RideFormat.distanceKm(Double.NEGATIVE_INFINITY, esCl))
    }

    @Test
    fun elevationAndAltitude_handleInfinityAndKeepZero() {
        assertEquals("—", RideFormat.elevationM(Double.POSITIVE_INFINITY, esCl))
        assertEquals("—", RideFormat.elevationM(Double.NEGATIVE_INFINITY, esCl))
        assertEquals("0 m", RideFormat.elevationM(0.0, esCl))
        assertEquals("—", RideFormat.altitudeM(Double.NEGATIVE_INFINITY, esCl))
        assertEquals("0 m", RideFormat.altitudeM(0.0, esCl))
    }

    @Test
    fun duration_handlesLongExtremesWithoutOverflowOrCrash() {
        assertEquals("0:00", RideFormat.duration(Long.MIN_VALUE))
        assertTrue(RideFormat.duration(Long.MAX_VALUE).isNotEmpty())
    }

    @Test
    fun caloriesBand_handlesEqualBoundsAndZero() {
        assertEquals(
            "≈ 310–310 kcal",
            RideFormat.caloriesBand(summary(310.0, 310.0), hasProfile = false, locale = esCl)?.value,
        )
        assertEquals(
            "≈ 0–0 kcal",
            RideFormat.caloriesBand(summary(0.0, 0.0), hasProfile = false, locale = esCl)?.value,
        )
    }

    @Test
    fun caloriesBand_rejectsNegativeBoundsInsteadOfClampingThem() {
        assertNull(RideFormat.caloriesBand(summary(-1.0, 300.0), hasProfile = false, locale = esCl))
        assertNull(RideFormat.caloriesBand(summary(100.0, -1.0), hasProfile = false, locale = esCl))
    }

    @Test
    fun hostileLocales_neverLeakNaNAndKeepTheUnitSuffix() {
        val locales =
            listOf(
                esCl,
                Locale.forLanguageTag("ar-EG"),
                Locale.forLanguageTag("tr-TR"),
            )
        val unusable = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)
        locales.forEach { locale ->
            unusable.forEach { value ->
                assertEquals("—", RideFormat.distanceKm(value, locale))
                assertEquals("—", RideFormat.speedKmh(value, locale))
                assertEquals("—", RideFormat.elevationM(value, locale))
            }

            val distance = RideFormat.distanceKm(12_345.6, locale)
            val speed = RideFormat.speedKmh(3.6, locale)
            val elevation = RideFormat.elevationM(1_240.0, locale)
            listOf(distance, speed, elevation).forEach { text ->
                assertFalse("unexpected NaN/Infinity in: $text", text.contains("NaN"))
                assertFalse("unexpected NaN/Infinity in: $text", text.contains("∞"))
            }
            assertTrue(distance.endsWith(" km"))
            assertTrue(speed.endsWith(" km/h"))
            assertTrue(elevation.endsWith(" m"))
        }
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
