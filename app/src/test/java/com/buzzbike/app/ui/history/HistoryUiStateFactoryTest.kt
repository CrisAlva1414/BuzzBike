package com.buzzbike.app.ui.history

import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.history.WeeklySummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** JVM tests for [HistoryUiStateFactory]. */
class HistoryUiStateFactoryTest {
    private val referenceMillis =
        LocalDate
            .of(2026, 10, 28)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    @Test
    fun given_emptyList_when_build_then_emptyWithZeroWeek() {
        val state = HistoryUiStateFactory.build(emptyList(), referenceMillis, ZoneOffset.UTC)

        assertEquals(HistoryUiState.Empty(WeeklySummary(distanceM = 0.0, rideCount = 0)), state)
    }

    @Test
    fun given_onlyRidesOutsideWeek_when_build_then_contentWithZeroWeek() {
        val oldRide = ride(startMillis = referenceMillis - FOURTEEN_DAYS_MILLIS, distanceM = 5_000.0)

        val state = HistoryUiStateFactory.build(listOf(oldRide), referenceMillis, ZoneOffset.UTC)

        assertEquals(
            HistoryUiState.Content(
                rides = listOf(oldRide),
                weekly = WeeklySummary(distanceM = 0.0, rideCount = 0),
            ),
            state,
        )
    }

    @Test
    fun given_ridesInWeek_when_build_then_contentWithWeeklySummary() {
        val rides =
            listOf(
                ride(startMillis = referenceMillis, distanceM = 1_000.0),
                ride(startMillis = referenceMillis + 1, distanceM = 2_000.0),
            )

        val state = HistoryUiStateFactory.build(rides, referenceMillis, ZoneOffset.UTC)

        assertEquals(
            HistoryUiState.Content(
                rides = rides,
                weekly = WeeklySummary(distanceM = 3_000.0, rideCount = 2),
            ),
            state,
        )
    }

    private fun ride(
        startMillis: Long,
        distanceM: Double,
    ): RideRecord =
        RideRecord(
            id = "ride-$startMillis",
            startTimeMillis = startMillis,
            endTimeMillis = null,
            distanceM = distanceM,
            movingTimeSeconds = 0L,
            stoppedTimeSeconds = 0L,
            elevationGainM = 0.0,
            elevationLossM = 0.0,
            avgSpeedMps = 0.0,
            maxSpeedMps = 0.0,
            calories = null,
        )

    private companion object {
        const val FOURTEEN_DAYS_MILLIS = 14L * 24 * 60 * 60 * 1_000
    }
}
