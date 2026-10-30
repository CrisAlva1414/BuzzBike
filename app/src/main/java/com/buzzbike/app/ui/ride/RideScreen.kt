@file:Suppress("TooManyFunctions")

package com.buzzbike.app.ui.ride

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.buzzbike.app.domain.calories.CalorieSummary
import com.buzzbike.app.domain.ride.session.GpsStatus
import com.buzzbike.app.domain.ride.session.LiveMetrics
import com.buzzbike.app.domain.ride.session.RideSessionState
import com.buzzbike.app.domain.ride.session.RideSummary
import com.buzzbike.app.ui.components.BuzzButton
import com.buzzbike.app.ui.components.BuzzDisplay
import com.buzzbike.app.ui.components.HoldToStopButton
import com.buzzbike.app.ui.components.MetricCell
import com.buzzbike.app.ui.components.StatusBanner
import com.buzzbike.app.ui.components.StatusTone
import com.buzzbike.app.ui.theme.BuzzDimens
import com.buzzbike.app.ui.theme.BuzzTheme
import java.util.Locale

private const val TRACE_HEIGHT_DP = 230
private const val CONTENT_PADDING_DP = 16
private const val SECTION_GAP_DP = 16
private const val GRID_GAP_DP = 8
private const val DIALOG_PADDING_DP = 20
private const val LOADING_TOP_PADDING_DP = 32
private const val METRIC_COLUMNS = 2
private const val PAUSED_SPEED_MPS = 0.0
private const val CALORIES_LABEL = "Calorías"
private const val DISTANCE_LABEL = "Distancia"
private const val MOVING_TIME_LABEL = "En movimiento"
private const val STOPPED_TIME_LABEL = "Detenido"
private const val ELEVATION_LABEL = "Desnivel"
private const val ELEVATION_GAIN_LABEL = "Desnivel +"
private const val ELEVATION_LOSS_LABEL = "Desnivel −"
private const val AVG_SPEED_LABEL = "Velocidad media"
private const val MAX_SPEED_LABEL = "Velocidad máxima"
private const val LOADING_TEXT = "Cargando…"
private const val START_LABEL = "INICIAR RIDE"
private const val PAUSE_LABEL = "PAUSA"
private const val RESUME_LABEL = "SEGUIR"
private const val SAVE_LABEL = "GUARDAR"
private const val DISCARD_LABEL = "DESCARTAR"
private const val CONFIRM_DISCARD_LABEL = "DESCARTAR DEFINITIVAMENTE"
private const val CANCEL_LABEL = "CANCELAR"
private const val RECOVER_LABEL = "RECUPERAR"
private const val SETTINGS_LABEL = "Ajustes de sync"
private const val AUTO_PAUSE_TEXT = "Pausa automática"
private const val PAUSED_TEXT = "En pausa"
private const val FINISHED_TITLE = "RIDE TERMINADO"
private const val APP_TITLE = "BUZZBIKE"
private const val RECOVERABLE_TITLE = "Se interrumpió un ride"
private const val RECOVERABLE_MESSAGE =
    "Se encontró un ride sin terminar guardado en el dispositivo. " +
        "Podés recuperar los puntos y ver el resumen, o descartarlo."
private const val DISCARD_TITLE = "Descartar ride"
private const val DISCARD_MESSAGE =
    "Se borrarán este ride y sus métricas. Esta acción no se puede deshacer."
private const val DISCARD_RECOVERABLE_TITLE = "Descartar ride interrumpido"
private const val DISCARD_RECOVERABLE_MESSAGE =
    "Se borrarán los puntos guardados de este ride. Esta acción no se puede deshacer."

@Composable
fun RideScreen(
    onOpenSettings: () -> Unit,
    viewModel: RideViewModel = hiltViewModel(),
) {
    val sessionState by viewModel.state.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    var permissionResult by rememberSaveable {
        mutableStateOf(LocationPermissionRequestResult.NOT_REQUESTED)
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            permissionResult =
                LocationPermissionUi.classify(
                    fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true,
                    coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
                )
            viewModel.onLocationPermissionResult()
        }
    val onRequestPermission = {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    KeepScreenOn(sessionState)

    RideContent(
        state = sessionState,
        locale = locale,
        permissionResult = permissionResult,
        onRequestPermission = onRequestPermission,
        onOpenSettings = onOpenSettings,
        onStart = viewModel::startRide,
        onPause = viewModel::pauseRide,
        onResume = viewModel::resumeRide,
        onStop = viewModel::stopRide,
        onSave = viewModel::acknowledgeFinished,
        onDiscard = viewModel::discardRide,
        onSalvage = viewModel::salvageRecoverableRide,
    )
}

/** Mantiene la pantalla encendida solo mientras se graba o se está en pausa. */
@Composable
private fun KeepScreenOn(state: RideSessionState) {
    val view = LocalView.current
    DisposableEffect(view, state) {
        val previous = view.keepScreenOn
        view.keepScreenOn = state is RideSessionState.Recording || state is RideSessionState.Paused
        onDispose { view.keepScreenOn = previous }
    }
}

@Composable
@Suppress("LongParameterList")
private fun RideContent(
    state: RideSessionState,
    locale: Locale,
    permissionResult: LocationPermissionRequestResult,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onSalvage: () -> Unit,
) {
    val colors = BuzzTheme.colors
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showRecoveryDiscardConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        showDiscardConfirm = false
        showRecoveryDiscardConfirm = false
    }

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
        when (state) {
            is RideSessionState.Idle -> IdleContent()
            is RideSessionState.Ready ->
                ReadyContent(
                    state = state,
                    permissionResult = permissionResult,
                    onRequestPermission = onRequestPermission,
                    onStart = onStart,
                    onOpenSettings = onOpenSettings,
                )
            is RideSessionState.Recording ->
                ActiveRideContent(
                    live = state.live,
                    speedText = RideFormat.speedKmh(state.live.speedMps, locale),
                    bannerText = if (state.autoPaused) AUTO_PAUSE_TEXT else null,
                    bannerTone = StatusTone.WARNING,
                    primaryLabel = PAUSE_LABEL,
                    onPrimary = onPause,
                    onStop = onStop,
                    locale = locale,
                )
            is RideSessionState.Paused ->
                ActiveRideContent(
                    live = state.live,
                    speedText = RideFormat.speedKmh(PAUSED_SPEED_MPS, locale),
                    bannerText = PAUSED_TEXT,
                    bannerTone = StatusTone.INFO,
                    primaryLabel = RESUME_LABEL,
                    onPrimary = onResume,
                    onStop = onStop,
                    locale = locale,
                )
            is RideSessionState.Finished ->
                FinishedContent(
                    summary = state.summary,
                    locale = locale,
                    onSave = onSave,
                    onDiscardRequest = { showDiscardConfirm = true },
                )
            is RideSessionState.Recoverable -> Unit
        }
    }

    if (state is RideSessionState.Finished && showDiscardConfirm) {
        DiscardDialog(
            title = DISCARD_TITLE,
            message = DISCARD_MESSAGE,
            onConfirm = {
                showDiscardConfirm = false
                onDiscard()
            },
            onCancel = { showDiscardConfirm = false },
        )
    }

    if (state is RideSessionState.Recoverable) {
        if (showRecoveryDiscardConfirm) {
            DiscardDialog(
                title = DISCARD_RECOVERABLE_TITLE,
                message = DISCARD_RECOVERABLE_MESSAGE,
                onConfirm = {
                    showRecoveryDiscardConfirm = false
                    onDiscard()
                },
                onCancel = { showRecoveryDiscardConfirm = false },
            )
        } else {
            RecoverableDialog(
                onRecover = onSalvage,
                onDiscardRequest = { showRecoveryDiscardConfirm = true },
            )
        }
    }
}

@Composable
private fun IdleContent() {
    Text(
        text = LOADING_TEXT,
        style = MaterialTheme.typography.bodyLarge,
        color = BuzzTheme.colors.ink,
        modifier = Modifier.padding(top = LOADING_TOP_PADDING_DP.dp),
    )
}

@Composable
private fun ReadyContent(
    state: RideSessionState.Ready,
    permissionResult: LocationPermissionRequestResult,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = BuzzTheme.colors
    val hasPermission = state.gps != GpsStatus.NO_PERMISSION
    val prompt = LocationPermissionUi.resolve(state.gps, permissionResult)

    BuzzDisplay(
        text = APP_TITLE,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = gpsLabel(state.gps),
        style = MaterialTheme.typography.bodyLarge,
        color = colors.ink,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = barometerLabel(state.hasBarometer),
        style = MaterialTheme.typography.bodyLarge,
        color = colors.ink,
        modifier = Modifier.fillMaxWidth(),
    )
    if (prompt.message != null) {
        StatusBanner(
            text = prompt.message,
            tone = StatusTone.DANGER,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (prompt.showGrantAction) {
        BuzzButton(
            text = prompt.grantActionLabel,
            onClick = onRequestPermission,
            modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
        )
    }
    BuzzButton(
        text = START_LABEL,
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
        enabled = hasPermission,
    )
    BuzzButton(
        text = SETTINGS_LABEL,
        onClick = onOpenSettings,
        modifier = Modifier.fillMaxWidth(),
        primary = false,
    )
}

@Composable
@Suppress("LongParameterList")
private fun ActiveRideContent(
    live: LiveMetrics,
    speedText: String,
    bannerText: String?,
    bannerTone: StatusTone,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    locale: Locale,
) {
    val colors = BuzzTheme.colors
    RideTrace(
        points = live.tailPoints,
        modifier = Modifier.fillMaxWidth().height(TRACE_HEIGHT_DP.dp),
    )
    BuzzDisplay(
        text = speedText,
        style = MaterialTheme.typography.displayLarge.copy(color = colors.ink),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    if (bannerText != null) {
        StatusBanner(
            text = bannerText,
            tone = bannerTone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    MetricGrid(liveMetricCells(live, locale))
    BuzzButton(
        text = primaryLabel,
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
    )
    HoldToStopButton(
        onConfirmed = onStop,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FinishedContent(
    summary: RideSummary,
    locale: Locale,
    onSave: () -> Unit,
    onDiscardRequest: () -> Unit,
) {
    BuzzDisplay(
        text = FINISHED_TITLE,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    MetricGrid(finishedMetricCells(summary, locale))
    BuzzButton(
        text = SAVE_LABEL,
        onClick = onSave,
        modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
    )
    DangerButton(
        text = DISCARD_LABEL,
        onClick = onDiscardRequest,
        modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
    )
}

@Composable
private fun MetricGrid(cells: List<MetricCellData>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
    ) {
        cells.chunked(METRIC_COLUMNS).forEach { rowCells ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
            ) {
                rowCells.forEach { cell ->
                    MetricCell(
                        label = cell.label,
                        value = cell.value,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowCells.size < METRIC_COLUMNS) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BuzzTheme.colors
    Box(
        modifier =
            modifier
                .heightIn(min = BuzzDimens.MinTouch)
                .background(colors.danger)
                .border(BuzzDimens.Border, colors.ink)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = CONTENT_PADDING_DP.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BuzzDisplay(
            text = text,
            style = MaterialTheme.typography.titleLarge.copy(color = colors.onDanger),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecoverableDialog(
    onRecover: () -> Unit,
    onDiscardRequest: () -> Unit,
) {
    BuzzDialog(
        title = RECOVERABLE_TITLE,
        message = RECOVERABLE_MESSAGE,
        dismissible = false,
        onDismissRequest = {},
    ) {
        BuzzButton(
            text = RECOVER_LABEL,
            onClick = onRecover,
            modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
        )
        DangerButton(
            text = DISCARD_LABEL,
            onClick = onDiscardRequest,
            modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
        )
    }
}

@Composable
private fun DiscardDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    BuzzDialog(
        title = title,
        message = message,
        dismissible = true,
        onDismissRequest = onCancel,
    ) {
        DangerButton(
            text = CONFIRM_DISCARD_LABEL,
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
        )
        BuzzButton(
            text = CANCEL_LABEL,
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            primary = false,
        )
    }
}

@Composable
private fun BuzzDialog(
    title: String,
    message: String,
    dismissible: Boolean,
    onDismissRequest: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val colors = BuzzTheme.colors
    Dialog(
        onDismissRequest = { if (dismissible) onDismissRequest() },
        properties =
            DialogProperties(
                dismissOnBackPress = dismissible,
                dismissOnClickOutside = dismissible,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.ground)
                    .border(BuzzDimens.Border, colors.ink)
                    .padding(DIALOG_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
        ) {
            BuzzDisplay(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
            )
            actions()
        }
    }
}

private fun gpsLabel(gps: GpsStatus): String =
    when (gps) {
        GpsStatus.NO_PERMISSION -> "GPS: sin permiso"
        GpsStatus.SEARCHING -> "GPS: buscando señal"
        GpsStatus.FIX -> "GPS: señal lista"
    }

private fun barometerLabel(hasBarometer: Boolean): String =
    if (hasBarometer) "Barómetro: disponible" else "Barómetro: sin sensor"

private fun liveMetricCells(
    live: LiveMetrics,
    locale: Locale,
): List<MetricCellData> =
    listOf(
        MetricCellData(DISTANCE_LABEL, RideFormat.distanceKm(live.distanceM, locale)),
        MetricCellData(MOVING_TIME_LABEL, RideFormat.duration(live.movingSeconds)),
        MetricCellData(ELEVATION_LABEL, RideFormat.elevationM(live.elevationGainM, locale)),
        calorieCell(live.calories, live.hasProfile, locale),
    )

private fun finishedMetricCells(
    summary: RideSummary,
    locale: Locale,
): List<MetricCellData> {
    val descriptors = summary.descriptors
    val elevation = summary.elevation
    return listOf(
        MetricCellData(DISTANCE_LABEL, RideFormat.distanceKm(descriptors.distanceM, locale)),
        MetricCellData(MOVING_TIME_LABEL, RideFormat.duration(descriptors.movingSeconds)),
        MetricCellData(STOPPED_TIME_LABEL, RideFormat.duration(descriptors.stoppedSeconds)),
        MetricCellData(ELEVATION_GAIN_LABEL, RideFormat.elevationM(elevation.gainM, locale)),
        MetricCellData(ELEVATION_LOSS_LABEL, RideFormat.elevationM(elevation.lossM, locale)),
        MetricCellData(AVG_SPEED_LABEL, RideFormat.speedKmh(descriptors.avgSpeedMps, locale)),
        MetricCellData(MAX_SPEED_LABEL, RideFormat.speedKmh(descriptors.maxSpeedMps, locale)),
        calorieCell(summary.calories, summary.hasProfile, locale),
    )
}

/**
 * La banda y el modelo se muestran juntos (ADR-001, AGENTS.md §6.4). Si no hay
 * estimación usable, la celda degrada a [RideFormat.UNKNOWN].
 */
private fun calorieCell(
    calories: CalorieSummary?,
    hasProfile: Boolean,
    locale: Locale,
): MetricCellData {
    val band = RideFormat.caloriesBand(calories, hasProfile, locale)
    return if (band == null) {
        MetricCellData(CALORIES_LABEL, RideFormat.UNKNOWN)
    } else {
        MetricCellData("$CALORIES_LABEL · ${band.model}", band.value)
    }
}

private data class MetricCellData(
    val label: String,
    val value: String,
)
