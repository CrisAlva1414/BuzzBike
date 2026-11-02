@file:Suppress("TooManyFunctions")

package com.buzzbike.app.ui.ride

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.buzzbike.app.domain.ride.session.GpsStatus
import com.buzzbike.app.domain.ride.session.LiveMetrics
import com.buzzbike.app.domain.ride.session.RideSessionState
import com.buzzbike.app.domain.ride.session.RideSummary
import com.buzzbike.app.ui.components.BuzzButton
import com.buzzbike.app.ui.components.BuzzDisplay
import com.buzzbike.app.ui.components.HoldToStopButton
import com.buzzbike.app.ui.components.MetricCellData
import com.buzzbike.app.ui.components.MetricGrid
import com.buzzbike.app.ui.components.RideMetricLabels
import com.buzzbike.app.ui.components.StatusBanner
import com.buzzbike.app.ui.components.StatusTone
import com.buzzbike.app.ui.components.calorieMetricCell
import com.buzzbike.app.ui.theme.BuzzDimens
import com.buzzbike.app.ui.theme.BuzzTheme
import java.util.Locale

private const val TRACE_HEIGHT_DP = 230
private const val CONTENT_PADDING_DP = 16
private const val SECTION_GAP_DP = 16
private const val ACTION_BAR_PADDING_DP = 12
private const val ACTION_BAR_GAP_DP = 8
private const val DIALOG_PADDING_DP = 20
private const val LOADING_TOP_PADDING_DP = 32
private const val PAUSED_SPEED_MPS = 0.0
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
private const val HISTORY_LABEL = "HISTORIAL"
private const val GARAGE_LABEL = "GARAJE"
private const val AUTO_PAUSE_TEXT = "Pausa automática"
private const val PAUSED_TEXT = "En pausa"
private const val FINISHED_TITLE = "RIDE TERMINADO"
private const val APP_TITLE = "BUZZBIKE"
private const val BIKE_SELECTOR_TITLE = "BICI DEL RIDE"
private const val BIKE_SELECTOR_LABEL = "Bici"
private const val NO_BIKES_TEXT = "Todavía no tenés bicis en el garaje."
private const val OPTION_GAP_DP = 8
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
    onOpenHistory: () -> Unit,
    onOpenGarage: () -> Unit,
    viewModel: RideViewModel = hiltViewModel(),
) {
    val sessionState by viewModel.state.collectAsState()
    val bikeSelection by viewModel.bikeSelection.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    var permissionResult by rememberSaveable {
        mutableStateOf(LocationPermissionRequestResult.NOT_REQUESTED)
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            permissionResult =
                LocationPermissionUi.classify(
                    fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true,
                    coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
                    shouldShowRationale =
                        context.findActivity()?.shouldShowRequestPermissionRationale(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) ?: true,
                )
            viewModel.permission.onLocationPermissionResult()
        }
    val onRequestPermission = {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }
    val onOpenAppSettings = { openAppSettings(context) }

    // Returning from the permission dialog or from the app settings may have
    // changed the grant, so re-evaluate the Ready state instead of keeping a
    // stale NO_PERMISSION banner.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.permission.onResume()
    }

    KeepScreenOn(sessionState)

    RideContent(
        state = sessionState,
        bikeSelection = bikeSelection,
        locale = locale,
        permissionResult = permissionResult,
        onRequestPermission = onRequestPermission,
        onOpenAppSettings = onOpenAppSettings,
        onOpenSettings = onOpenSettings,
        onOpenHistory = onOpenHistory,
        onOpenGarage = onOpenGarage,
        onSelectBike = viewModel::selectBike,
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
    bikeSelection: RideBikeSelection,
    locale: Locale,
    permissionResult: LocationPermissionRequestResult,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenGarage: () -> Unit,
    onSelectBike: (String?) -> Unit,
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

    // The action buttons live outside the scroll area: on a 320 dp wide screen
    // TERMINAR/PAUSA/INICIAR must never require scrolling during a live ride.
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.ground)
                .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
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
                            bikeSelection = bikeSelection,
                            permissionResult = permissionResult,
                            onRequestPermission = onRequestPermission,
                            onOpenAppSettings = onOpenAppSettings,
                            onSelectBike = onSelectBike,
                            onOpenSettings = onOpenSettings,
                            onOpenHistory = onOpenHistory,
                            onOpenGarage = onOpenGarage,
                        )
                    is RideSessionState.Recording ->
                        ActiveRideContent(
                            live = state.live,
                            speedText = RideFormat.speedKmh(state.live.speedMps, locale),
                            bannerText = if (state.autoPaused) AUTO_PAUSE_TEXT else null,
                            bannerTone = StatusTone.WARNING,
                            locale = locale,
                        )
                    is RideSessionState.Paused ->
                        ActiveRideContent(
                            live = state.live,
                            speedText = RideFormat.speedKmh(PAUSED_SPEED_MPS, locale),
                            bannerText = PAUSED_TEXT,
                            bannerTone = StatusTone.INFO,
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
        }
        RideActionBar(
            state = state,
            onStart = onStart,
            onPause = onPause,
            onResume = onResume,
            onStop = onStop,
        )
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

/**
 * Fixed bottom action bar.
 *
 * Keeps the primary ride action (INICIAR, PAUSA/SEGUIR) and the hold-to-stop
 * control pinned above the scrolling content, so a 320x640 screen with large
 * fonts still shows them without scrolling. States without a pinned action
 * (Idle, Finished, Recoverable) render nothing here.
 */
@Composable
private fun RideActionBar(
    state: RideSessionState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = BuzzTheme.colors
    val primaryLabel: String? =
        when (state) {
            is RideSessionState.Ready -> START_LABEL
            is RideSessionState.Recording -> PAUSE_LABEL
            is RideSessionState.Paused -> RESUME_LABEL
            else -> null
        }
    if (primaryLabel == null) return
    val onPrimary =
        when (state) {
            is RideSessionState.Ready -> onStart
            is RideSessionState.Recording -> onPause
            is RideSessionState.Paused -> onResume
            else -> return
        }
    val primaryEnabled = state !is RideSessionState.Ready || state.gps != GpsStatus.NO_PERMISSION

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.ground)
                .padding(horizontal = CONTENT_PADDING_DP.dp, vertical = ACTION_BAR_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(ACTION_BAR_GAP_DP.dp),
    ) {
        BuzzButton(
            text = primaryLabel,
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
            enabled = primaryEnabled,
        )
        if (state is RideSessionState.Recording || state is RideSessionState.Paused) {
            HoldToStopButton(
                onConfirmed = onStop,
                modifier = Modifier.fillMaxWidth(),
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
    bikeSelection: RideBikeSelection,
    permissionResult: LocationPermissionRequestResult,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSelectBike: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenGarage: () -> Unit,
) {
    val colors = BuzzTheme.colors
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
    if (prompt.showOpenSettingsAction) {
        BuzzButton(
            text = prompt.openSettingsActionLabel,
            onClick = onOpenAppSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = BuzzDimens.RideActionHeight),
            primary = false,
        )
    }
    BikeSelector(selection = bikeSelection, onSelect = onSelectBike)
    BuzzButton(
        text = SETTINGS_LABEL,
        onClick = onOpenSettings,
        modifier = Modifier.fillMaxWidth(),
        primary = false,
    )
    BuzzButton(
        text = HISTORY_LABEL,
        onClick = onOpenHistory,
        modifier = Modifier.fillMaxWidth(),
        primary = false,
    )
    BuzzButton(
        text = GARAGE_LABEL,
        onClick = onOpenGarage,
        modifier = Modifier.fillMaxWidth(),
        primary = false,
    )
}

@Composable
private fun BikeSelector(
    selection: RideBikeSelection,
    onSelect: (String?) -> Unit,
) {
    val colors = BuzzTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OPTION_GAP_DP.dp),
    ) {
        BuzzDisplay(
            text = BIKE_SELECTOR_TITLE,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "$BIKE_SELECTOR_LABEL: ${selection.selectedLabel}",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
            modifier = Modifier.fillMaxWidth(),
        )
        BikeOption(
            text = RideBikeSelection.NO_BIKE_LABEL,
            selected = selection.selectedBikeId == null,
            enabled = !selection.isBusy,
            description = "Sin bici: no asignar este ride a ninguna bici",
            onClick = { onSelect(null) },
        )
        selection.bikes.forEach { bike ->
            BikeOption(
                text = bike.name,
                selected = bike.id == selection.selectedBikeId,
                enabled = !selection.isBusy,
                description = "Asignar este ride a la bici ${bike.name}",
                onClick = { onSelect(bike.id) },
            )
        }
        if (selection.bikes.isEmpty()) {
            Text(
                text = NO_BIKES_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BikeOption(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val stateDescription = if (selected) "Seleccionada" else "No seleccionada"
    BuzzButton(
        text = text,
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "$description. $stateDescription"
                    this.selected = selected
                },
        enabled = enabled,
        primary = selected,
    )
}

@Composable
@Suppress("LongParameterList")
private fun ActiveRideContent(
    live: LiveMetrics,
    speedText: String,
    bannerText: String?,
    bannerTone: StatusTone,
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
        MetricCellData(RideMetricLabels.DISTANCE, RideFormat.distanceKm(live.distanceM, locale)),
        MetricCellData(RideMetricLabels.MOVING_TIME, RideFormat.duration(live.movingSeconds)),
        MetricCellData(RideMetricLabels.ELEVATION, RideFormat.elevationM(live.elevationGainM, locale)),
        calorieMetricCell(live.calories, live.hasProfile, locale),
    )

private fun finishedMetricCells(
    summary: RideSummary,
    locale: Locale,
): List<MetricCellData> {
    val descriptors = summary.descriptors
    val elevation = summary.elevation
    return listOf(
        MetricCellData(RideMetricLabels.DISTANCE, RideFormat.distanceKm(descriptors.distanceM, locale)),
        MetricCellData(RideMetricLabels.MOVING_TIME, RideFormat.duration(descriptors.movingSeconds)),
        MetricCellData(RideMetricLabels.STOPPED_TIME, RideFormat.duration(descriptors.stoppedSeconds)),
        MetricCellData(RideMetricLabels.ELEVATION_GAIN, RideFormat.elevationM(elevation.gainM, locale)),
        MetricCellData(RideMetricLabels.ELEVATION_LOSS, RideFormat.elevationM(elevation.lossM, locale)),
        MetricCellData(RideMetricLabels.AVG_SPEED, RideFormat.speedKmh(descriptors.avgSpeedMps, locale)),
        MetricCellData(RideMetricLabels.MAX_SPEED, RideFormat.speedKmh(descriptors.maxSpeedMps, locale)),
        calorieMetricCell(summary.calories, summary.hasProfile, locale),
    )
}

/** Unwraps the Activity from a Context so the permission rationale can be read. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
