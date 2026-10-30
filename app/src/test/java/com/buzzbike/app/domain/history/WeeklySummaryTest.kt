package com.buzzbike.app.domain.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * JVM-pure tests for [WeeklySummaryCalculator] (AGENTS.md §8).
 *
 * Week window is Monday 00:00 (inclusive) through Sunday 23:59:59.999
 * (inclusive); the following Monday 00:00 is excluded.
 */
class WeeklySummaryTest {
    @Test
    fun given_ridesAtBothEndsOfWeek_when_summarized_then_boundariesAreInclusiveAndNextMondayExcluded() {
        val weekStart =
            LocalDate
                .of(2026, 10, 26)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val weekEnd =
            LocalDate
                .of(2026, 11, 2)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val lastMillis = weekEnd - 1
        val nextMonday = weekEnd
        val rides =
            listOf(
                ride(startMillis = weekStart, distanceM = 1_000.0),
                ride(startMillis = lastMillis, distanceM = 2_000.0),
                ride(startMillis = nextMonday, distanceM = 4_000.0),
            )

        val summary = WeeklySummaryCalculator.summarize(rides, referenceMillis = weekStart, zoneId = ZoneOffset.UTC)

        assertEquals(WeeklySummary(distanceM = 3_000.0, rideCount = 2), summary)
    }

    @Test
    fun given_rideFromPreviousWeek_when_summarized_then_excluded() {
        val reference =
            LocalDate
                .of(2026, 10, 28)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val previousMonday =
            LocalDate
                .of(2026, 10, 19)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

        val summary =
            WeeklySummaryCalculator.summarize(
                rides = listOf(ride(startMillis = previousMonday, distanceM = 500.0)),
                referenceMillis = reference,
                zoneId = ZoneOffset.UTC,
            )

        assertEquals(WeeklySummary(distanceM = 0.0, rideCount = 0), summary)
    }

    @Test
    fun given_severalRidesInWeek_when_summarized_then_distancesAndCountAggregate() {
        val reference =
            LocalDate
                .of(2026, 10, 28)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val rides =
            listOf(
                ride(startMillis = reference, distanceM = 1_500.0),
                ride(startMillis = reference + 1, distanceM = 2_500.0),
                ride(startMillis = reference + 2, distanceM = 3_000.0),
            )

        val summary = WeeklySummaryCalculator.summarize(rides, referenceMillis = reference, zoneId = ZoneOffset.UTC)

        assertEquals(WeeklySummary(distanceM = 7_000.0, rideCount = 3), summary)
    }

    @Test
    fun given_invalidDistances_when_summarized_then_countedButNotSummed() {
        val reference =
            LocalDate
                .of(2026, 10, 28)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val rides =
            listOf(
                ride(startMillis = reference, distanceM = Double.NaN),
                ride(startMillis = reference + 1, distanceM = -5.0),
                ride(startMillis = reference + 2, distanceM = 10.0),
            )

        val summary = WeeklySummaryCalculator.summarize(rides, referenceMillis = reference, zoneId = ZoneOffset.UTC)

        assertEquals(WeeklySummary(distanceM = 10.0, rideCount = 3), summary)
    }

    @Test
    fun given_santiagoZone_when_summarized_then_weekBoundaryIsLocalMonday() {
        // A ride at 01:00Z on Monday 2026-01-12 is Sunday 22:00 in Santiago
        // (UTC-3 in January), so it belongs to the previous local week even
        // though a UTC calculation would include it.
        val santiago = ZoneId.of("America/Santiago")
        val localSundayNight = ZonedDateTime.of(LocalDateTime.of(2026, 1, 12, 1, 0), ZoneOffset.UTC)
        val localMonday = ZonedDateTime.of(LocalDateTime.of(2026, 1, 12, 4, 0), ZoneOffset.UTC)
        val nextLocalMonday = ZonedDateTime.of(LocalDateTime.of(2026, 1, 19, 3, 0), ZoneOffset.UTC)
        val referenceMillis =
            ZonedDateTime
                .of(LocalDateTime.of(2026, 1, 14, 12, 0), ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val rides =
            listOf(
                ride(startMillis = localSundayNight.toInstant().toEpochMilli(), distanceM = 111.0),
                ride(startMillis = localMonday.toInstant().toEpochMilli(), distanceM = 222.0),
                ride(startMillis = nextLocalMonday.toInstant().toEpochMilli(), distanceM = 333.0),
            )

        val summary = WeeklySummaryCalculator.summarize(rides, referenceMillis = referenceMillis, zoneId = santiago)

        assertEquals(WeeklySummary(distanceM = 222.0, rideCount = 1), summary)
    }

    @Test
    fun given_emptyList_when_summarized_then_zeroSummary() {
        val summary =
            WeeklySummaryCalculator.summarize(
                rides = emptyList(),
                referenceMillis =
                    LocalDate
                        .of(2026, 10, 28)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli(),
                zoneId = ZoneOffset.UTC,
            )

        assertEquals(WeeklySummary(distanceM = 0.0, rideCount = 0), summary)
    }

    @Test
    fun given_santiagoWeekWithDstSpringForward_when_summarized_thenLocalMondayBoundariesHold() {
        // America/Santiago springs forward on Sunday 2026-09-06 (00:00 -> 01:00),
        // so this local week is only 167 h long and the next Monday starts at
        // 03:00Z (-03:00) instead of 04:00Z. A naive UTC week would shift both ends.
        val santiago = ZoneId.of("America/Santiago")
        val referenceMillis =
            LocalDate
                .of(2026, 9, 2)
                .atStartOfDay(santiago)
                .toInstant()
                .toEpochMilli()
        val weekStart =
            LocalDate
                .of(2026, 8, 31)
                .atStartOfDay(santiago)
                .toInstant()
                .toEpochMilli()
        val weekEnd =
            LocalDate
                .of(2026, 9, 7)
                .atStartOfDay(santiago)
                .toInstant()
                .toEpochMilli()

        assertEquals(167L * 3_600L, (weekEnd - weekStart) / 1_000L)
        assertEquals(
            LocalDateTime.of(2026, 9, 6, 23, 59, 59, 999_000_000),
            Instant.ofEpochMilli(weekEnd - 1).atZone(santiago).toLocalDateTime(),
        )

        val rides =
            listOf(
                ride(startMillis = weekStart - 1, distanceM = 1.0),
                ride(startMillis = weekStart, distanceM = 2.0),
                ride(startMillis = weekEnd - 1, distanceM = 4.0),
                ride(startMillis = weekEnd, distanceM = 8.0),
            )

        val summary = WeeklySummaryCalculator.summarize(rides, referenceMillis = referenceMillis, zoneId = santiago)

        assertEquals(WeeklySummary(distanceM = 6.0, rideCount = 2), summary)
    }

    private fun ride(
        startMillis: Long,
        distanceM: Double = 0.0,
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
}
