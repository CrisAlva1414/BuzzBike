package com.buzzbike.app.domain.history

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Aggregated distance and ride count for one calendar week. */
data class WeeklySummary(
    val distanceM: Double,
    val rideCount: Int,
)

/**
 * Aggregates rides into the calendar week that contains [referenceMillis].
 *
 * The window is Monday 00:00 through Sunday 23:59:59.999 in [zoneId]; the
 * following Monday 00:00 is excluded. Only rides whose `startTimeMillis` falls
 * in `[weekStart, weekEnd)` are considered. [referenceMillis] is injected — the
 * calculator never reads the wall clock, which keeps it deterministic and
 * JVM-testable.
 */
object WeeklySummaryCalculator {
    fun summarize(
        rides: List<RideRecord>,
        referenceMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): WeeklySummary {
        val referenceDate = Instant.ofEpochMilli(referenceMillis).atZone(zoneId).toLocalDate()
        val weekStart =
            referenceDate
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zoneId)
        val weekStartMillis = weekStart.toInstant().toEpochMilli()
        val weekEndMillis = weekStart.plusWeeks(ONE_WEEK).toInstant().toEpochMilli()

        val weekRides = rides.filter { it.startTimeMillis in weekStartMillis until weekEndMillis }
        val distanceM =
            weekRides
                .map(RideRecord::distanceM)
                .filter { it.isFinite() && it >= MIN_DISTANCE_M }
                .sum()

        return WeeklySummary(distanceM = distanceM, rideCount = weekRides.size)
    }
}

private const val ONE_WEEK = 1L
private const val MIN_DISTANCE_M = 0.0
