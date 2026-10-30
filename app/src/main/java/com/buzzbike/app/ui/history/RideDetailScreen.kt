package com.buzzbike.app.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buzzbike.app.domain.history.RideDetail
import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.ride.session.LatLon
import com.buzzbike.app.ui.components.BuzzButton
import com.buzzbike.app.ui.components.BuzzDisplay
import com.buzzbike.app.ui.components.MetricCellData
import com.buzzbike.app.ui.components.MetricGrid
import com.buzzbike.app.ui.components.RideMetricLabels
import com.buzzbike.app.ui.components.StatusBanner
import com.buzzbike.app.ui.components.StatusTone
import com.buzzbike.app.ui.components.calorieMetricCell
import com.buzzbike.app.ui.ride.RideFormat
import com.buzzbike.app.ui.ride.RideTrace
import com.buzzbike.app.ui.theme.BuzzTheme
import java.util.Locale

private const val CONTENT_PADDING_DP = 16
private const val SECTION_GAP_DP = 16
private const val TRACE_HEIGHT_DP = 230
private const val SCREEN_TITLE = "DETALLE"
private const val BACK_LABEL = "VOLVER"
private const val LOADING_TEXT = "Cargando…"
private const val NOT_FOUND_TEXT = "No se encontró el ride."
private const val EMPTY_POINTS_TEXT = "Este ride no tiene puntos para exportar."
private const val EXPORT_SUCCESS_TEXT = "GPX exportado."
private const val EXPORT_LABEL = "EXPORTAR GPX"
private const val EXPORTING_LABEL = "EXPORTANDO…"
private const val GPX_MIME_TYPE = "application/gpx+xml"

/**
 * Detail screen of one completed ride, with GPX export through the Storage
 * Access Framework.
 *
 * The screen only renders state exposed by [RideDetailViewModel]; it never
 * touches Room or the file system directly.
 */
@Composable
fun RideDetailScreen(
    onBack: () -> Unit,
    viewModel: RideDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GPX_MIME_TYPE)) { uri ->
            uri?.let { viewModel.exportGpx(it.toString()) }
        }

    RideDetailContent(
        state = state,
        exportState = exportState,
        locale = locale,
        onBack = onBack,
        onExport = { startTimeMillis ->
            exportLauncher.launch(RideHistoryFormat.gpxFileName(startTimeMillis))
        },
    )
}

@Composable
private fun RideDetailContent(
    state: RideDetailUiState,
    exportState: GpxExportState,
    locale: Locale,
    onBack: () -> Unit,
    onExport: (Long) -> Unit,
) {
    val colors = BuzzTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.ground)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(CONTENT_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
    ) {
        DetailHeader(onBack = onBack)
        when (state) {
            is RideDetailUiState.Loading -> Message(LOADING_TEXT)
            is RideDetailUiState.NotFound -> Message(NOT_FOUND_TEXT)
            is RideDetailUiState.Error ->
                StatusBanner(
                    text = state.message,
                    tone = StatusTone.DANGER,
                    modifier = Modifier.fillMaxWidth(),
                )
            is RideDetailUiState.Content ->
                DetailContent(
                    detail = state.detail,
                    exportState = exportState,
                    locale = locale,
                    onExport = onExport,
                )
        }
    }
}

@Composable
private fun DetailHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BuzzDisplay(
            text = SCREEN_TITLE,
            style = MaterialTheme.typography.headlineLarge,
        )
        BuzzButton(
            text = BACK_LABEL,
            onClick = onBack,
            primary = false,
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = BuzzTheme.colors.ink,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DetailContent(
    detail: RideDetail,
    exportState: GpxExportState,
    locale: Locale,
    onExport: (Long) -> Unit,
) {
    val ride = detail.ride
    val colors = BuzzTheme.colors
    Text(
        text = RideHistoryFormat.rideTitle(ride.startTimeMillis),
        style = MaterialTheme.typography.titleLarge,
        color = colors.ink,
        modifier = Modifier.fillMaxWidth(),
    )
    RideTrace(
        points = detail.points.map { LatLon(it.lat, it.lon) },
        modifier = Modifier.fillMaxWidth().height(TRACE_HEIGHT_DP.dp),
    )
    MetricGrid(cells = detailMetricCells(ride, locale))
    if (detail.points.isEmpty()) {
        StatusBanner(
            text = EMPTY_POINTS_TEXT,
            tone = StatusTone.INFO,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    when (exportState) {
        is GpxExportState.Success ->
            StatusBanner(
                text = EXPORT_SUCCESS_TEXT,
                tone = StatusTone.INFO,
                modifier = Modifier.fillMaxWidth(),
            )
        is GpxExportState.Error ->
            StatusBanner(
                text = exportState.message,
                tone = StatusTone.DANGER,
                modifier = Modifier.fillMaxWidth(),
            )
        GpxExportState.Idle, GpxExportState.Exporting -> Unit
    }
    BuzzButton(
        text = if (exportState == GpxExportState.Exporting) EXPORTING_LABEL else EXPORT_LABEL,
        onClick = { onExport(ride.startTimeMillis) },
        modifier = Modifier.fillMaxWidth(),
        enabled = detail.points.isNotEmpty() && exportState != GpxExportState.Exporting,
    )
}

/**
 * Metrics for the detail grid, in the same order as the finished-ride summary.
 *
 * [hasProfile] is hard-coded to `false` because v1 has no body profile (U3).
 * Once a persisted `calHasProfile` value exists, this function must use it
 * instead so the calorie band reports the right context.
 */
private fun detailMetricCells(
    ride: RideRecord,
    locale: Locale,
): List<MetricCellData> =
    listOf(
        MetricCellData(RideMetricLabels.DISTANCE, RideFormat.distanceKm(ride.distanceM, locale)),
        MetricCellData(RideMetricLabels.MOVING_TIME, RideFormat.duration(ride.movingTimeSeconds)),
        MetricCellData(RideMetricLabels.STOPPED_TIME, RideFormat.duration(ride.stoppedTimeSeconds)),
        MetricCellData(RideMetricLabels.ELEVATION_GAIN, RideFormat.elevationM(ride.elevationGainM, locale)),
        MetricCellData(RideMetricLabels.ELEVATION_LOSS, RideFormat.elevationM(ride.elevationLossM, locale)),
        MetricCellData(RideMetricLabels.AVG_SPEED, RideFormat.speedKmh(ride.avgSpeedMps, locale)),
        MetricCellData(RideMetricLabels.MAX_SPEED, RideFormat.speedKmh(ride.maxSpeedMps, locale)),
        calorieMetricCell(ride.calories, hasProfile = false, locale = locale),
    )
