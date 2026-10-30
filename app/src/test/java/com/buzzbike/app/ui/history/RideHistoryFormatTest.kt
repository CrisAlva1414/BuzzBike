package com.buzzbike.app.ui.history

import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.history.WeeklySummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/** JVM-pure tests for [RideHistoryFormat]. */
class RideHistoryFormatTest {
    private val spanish = Locale.forLanguageTag("es")

    @Test
    fun given_fixedInstantInUtc_when_rideTitle_then_spanishDateAnd24hTime() {
        val millis =
            ZonedDateTime
                .of(2026, 10, 30, 18, 42, 0, 0, ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

        assertEquals("30 de octubre de 2026 · 18:42", RideHistoryFormat.rideTitle(millis, ZoneId.of("UTC")))
    }

    @Test
    fun given_fixedInstantInUtc_when_gpxFileName_then_localDateAndMinutesWithoutSeconds() {
        val millis =
            ZonedDateTime
                .of(2026, 10, 30, 18, 42, 59, 0, ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

        assertEquals("buzzbike-20261030-1842.gpx", RideHistoryFormat.gpxFileName(millis, ZoneId.of("UTC")))
    }

    @Test
    fun given_pluralRides_when_weeklyLine_then_countUsesPlural() {
        val line = RideHistoryFormat.weeklyLine(WeeklySummary(distanceM = 12_350.0, rideCount = 3), spanish)

        assertEquals("Esta semana: 12,35 km en 3 rides", line)
    }

    @Test
    fun given_singleRide_when_weeklyLine_then_countUsesSingular() {
        val line = RideHistoryFormat.weeklyLine(WeeklySummary(distanceM = 12_350.0, rideCount = 1), spanish)

        assertEquals("Esta semana: 12,35 km en 1 ride", line)
    }

    @Test
    fun given_noRides_when_weeklyLine_then_zeroUsesPlural() {
        val line = RideHistoryFormat.weeklyLine(WeeklySummary(distanceM = 0.0, rideCount = 0), spanish)

        assertEquals("Esta semana: 0,00 km en 0 rides", line)
    }

    @Test
    fun given_ride_when_rowSubtitle_then_distanceAndDuration() {
        val ride =
            RideRecord(
                id = "ride-1",
                startTimeMillis = 0L,
                endTimeMillis = null,
                distanceM = 12_350.0,
                movingTimeSeconds = 2_700L,
                stoppedTimeSeconds = 0L,
                elevationGainM = 0.0,
                elevationLossM = 0.0,
                avgSpeedMps = 0.0,
                maxSpeedMps = 0.0,
                calories = null,
            )

        assertEquals("12,35 km · 45:00", RideHistoryFormat.rowSubtitle(ride, spanish))
    }
}
