package com.buzzbike.app.ui.history

import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.history.WeeklySummary
import com.buzzbike.app.ui.ride.RideFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SEPARATOR = " · "
private const val WEEKLY_PREFIX = "Esta semana: "
private const val RIDE_COUNT_SEPARATOR = " en "
private const val RIDE_SINGULAR = " ride"
private const val RIDE_PLURAL = " rides"

private val TITLE_FORMATTER =
    DateTimeFormatter
        .ofPattern("d 'de' MMMM 'de' yyyy · HH:mm", Locale.forLanguageTag("es"))
        .withZone(ZoneId.systemDefault())

private const val GPX_FILE_PREFIX = "buzzbike-"
private const val GPX_FILE_SUFFIX = ".gpx"
private val GPX_FILE_NAME_FORMATTER =
    DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
        .withZone(ZoneId.systemDefault())

/**
 * Pure, JVM-testable formatting for the history list.
 *
 * It only renders already-computed domain values and never imports `android.*`,
 * so it runs in plain JVM tests. Distances and durations reuse [RideFormat] to
 * keep one source of truth for units and rounding.
 */
object RideHistoryFormat {
    /**
     * Renders the ride start in Spanish, 24 h: `"30 de octubre de 2026 · 18:42"`.
     *
     * The zone is injected to keep tests deterministic; the formatter defaults
     * to the JVM's system zone.
     */
    fun rideTitle(
        startTimeMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = TITLE_FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(startTimeMillis))

    /**
     * Builds the default GPX file name from the local start time:
     * `"buzzbike-20261030-1842.gpx"`.
     *
     * Seconds are omitted because the filename is only a user-facing default;
     * two rides starting in the same minute still get distinct documents when
     * the storage layer disambiguates. The zone is injected for deterministic
     * tests.
     */
    fun gpxFileName(
        startTimeMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String =
        GPX_FILE_PREFIX +
            GPX_FILE_NAME_FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(startTimeMillis)) +
            GPX_FILE_SUFFIX

    /**
     * Renders the weekly strip: `"Esta semana: 12,35 km en 3 rides"`.
     *
     * Singular and zero use `"ride"` and `"rides"` respectively.
     */
    fun weeklyLine(
        summary: WeeklySummary,
        locale: Locale,
    ): String {
        val distance = RideFormat.distanceKm(summary.distanceM, locale)
        val rides = if (summary.rideCount == 1) RIDE_SINGULAR else RIDE_PLURAL
        return WEEKLY_PREFIX + distance + RIDE_COUNT_SEPARATOR + summary.rideCount + rides
    }

    /** Renders the row subtitle: `"12,35 km · 45:00"`. */
    fun rowSubtitle(
        ride: RideRecord,
        locale: Locale,
    ): String = RideFormat.distanceKm(ride.distanceM, locale) + SEPARATOR + RideFormat.duration(ride.movingTimeSeconds)
}
