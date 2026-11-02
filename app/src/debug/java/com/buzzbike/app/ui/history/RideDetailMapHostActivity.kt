package com.buzzbike.app.ui.history

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.buzzbike.app.domain.history.RideDetail
import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.ui.theme.BuzzBikeTheme
import java.util.Locale

private const val RIDE_ID = "instrumented-map-ride"
private const val RIDE_START_MILLIS = 1_700_000_000_000L
private const val RIDE_END_MILLIS = 1_700_000_600_000L
private const val RIDE_DISTANCE_M = 1_200.0
private const val RIDE_MOVING_SECONDS = 600L
private const val RIDE_ELEVATION_GAIN_M = 18.0
private const val RIDE_ELEVATION_LOSS_M = 12.0
private const val RIDE_AVG_SPEED_MPS = 5.0
private const val RIDE_MAX_SPEED_MPS = 8.0
private const val FIRST_LAT = -33.4489
private const val FIRST_LON = -70.6693
private const val SECOND_LAT = -33.4372
private const val SECOND_LON = -70.6506
private const val SECOND_TIMESTAMP_NANOS = 1_000_000_000L

/**
 * Debug-only host for the instrumented ride-map test.
 *
 * It renders [RideDetailContent] with a static fixture and the MAP tab already
 * selected, so the test exercises the real `MapView`/`RideMap` path without
 * needing Hilt, Room, navigation, or network access. A private fixture keeps
 * the test from depending on persisted state.
 */
class RideDetailMapHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BuzzBikeTheme {
                RideDetailContent(
                    state = RideDetailUiState.Content(detail = sampleDetail()),
                    exportState = GpxExportState.Idle,
                    locale = Locale.US,
                    onBack = {},
                    onExport = {},
                    initialTab = RideDetailTab.MAP,
                )
            }
        }
    }
}

private fun sampleDetail(): RideDetail =
    RideDetail(
        ride =
            RideRecord(
                id = RIDE_ID,
                startTimeMillis = RIDE_START_MILLIS,
                endTimeMillis = RIDE_END_MILLIS,
                distanceM = RIDE_DISTANCE_M,
                movingTimeSeconds = RIDE_MOVING_SECONDS,
                stoppedTimeSeconds = 0L,
                elevationGainM = RIDE_ELEVATION_GAIN_M,
                elevationLossM = RIDE_ELEVATION_LOSS_M,
                avgSpeedMps = RIDE_AVG_SPEED_MPS,
                maxSpeedMps = RIDE_MAX_SPEED_MPS,
                calories = null,
            ),
        points =
            listOf(
                TrackPoint(timestampMonotonicNanos = 0L, lat = FIRST_LAT, lon = FIRST_LON),
                TrackPoint(
                    timestampMonotonicNanos = SECOND_TIMESTAMP_NANOS,
                    lat = SECOND_LAT,
                    lon = SECOND_LON,
                ),
            ),
    )
