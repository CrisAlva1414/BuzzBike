package com.buzzbike.app.ui.garage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buzzbike.app.domain.wear.Bike
import com.buzzbike.app.domain.wear.WearStatus
import com.buzzbike.app.ui.components.BuzzButton
import com.buzzbike.app.ui.components.BuzzDisplay
import com.buzzbike.app.ui.components.StatusBanner
import com.buzzbike.app.ui.components.StatusTone
import com.buzzbike.app.ui.theme.BuzzDimens
import com.buzzbike.app.ui.theme.BuzzTheme
import java.util.Locale

private const val CONTENT_PADDING_DP = 16
private const val CARD_PADDING_DP = 12
private const val SECTION_GAP_DP = 16
private const val TEXT_GAP_DP = 4
private const val MIN_TOUCH_DP = 48
private const val SCREEN_TITLE = "GARAJE"
private const val BACK_LABEL = "VOLVER"
private const val ADD_BIKE_LABEL = "AGREGAR BICI"
private const val LOADING_TEXT = "Cargando…"
private const val EMPTY_TEXT = "Todavía no agregaste una bici. Creá una para trackear el desgaste de sus componentes."
private const val RENAME_LABEL = "RENOMBRAR"
private const val ARCHIVE_LABEL = "ARCHIVAR"
private const val RESTORE_LABEL = "RESTAURAR"
private const val DELETE_LABEL = "BORRAR"
private const val ARCHIVED_TEXT = "ARCHIVADA"
private const val SELECTED_TEXT = "BICI SELECCIONADA"
private const val DUE_ALERT_TEXT = "Hay componentes con cambio vencido. Revisá el garaje."
private const val APPROACHING_ALERT_TEXT = "Hay componentes próximos al cambio."

/**
 * Garage list plus the selected bike's detail.
 *
 * The whole screen scrolls because a bike card and its wear detail can grow
 * with the font scale; no text sits in a fixed-height box. Every mutating
 * control is disabled while [GarageUiState.Content.isBusy] is true.
 */
@Composable
fun GarageScreen(
    onBack: () -> Unit,
    viewModel: GarageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val form by viewModel.form.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    var pendingDelete by remember { mutableStateOf<Bike?>(null) }

    GarageContent(
        state = state,
        locale = locale,
        onBack = onBack,
        onSelectBike = viewModel::selectBike,
        onAddBike = { viewModel.openForm(GarageFormTarget.CreateBike) },
        onRenameBike = { viewModel.openForm(GarageFormTarget.RenameBike(it)) },
        onArchiveBike = viewModel::setBikeArchived,
        onDeleteBike = { pendingDelete = it },
        onAddComponent = { viewModel.openForm(GarageFormTarget.CreateComponent(it)) },
        onLogService = { viewModel.openForm(GarageFormTarget.ServiceComponent(it)) },
        onRetireComponent = viewModel::retireComponent,
    )

    when (val current = form) {
        null -> Unit
        is GarageForm.Bike ->
            BikeFormDialog(
                form = current,
                isBusy = isBusy(state),
                onChange = viewModel::updateForm,
                onSubmit = viewModel::submitForm,
                onDismiss = viewModel::closeForm,
            )
        is GarageForm.Component ->
            ComponentFormDialog(
                form = current,
                isBusy = isBusy(state),
                onChange = viewModel::updateForm,
                onSubmit = viewModel::submitForm,
                onDismiss = viewModel::closeForm,
            )
        is GarageForm.Service ->
            ServiceFormDialog(
                form = current,
                isBusy = isBusy(state),
                onChange = viewModel::updateForm,
                onSubmit = viewModel::submitForm,
                onDismiss = viewModel::closeForm,
            )
    }

    pendingDelete?.let { bike ->
        DeleteBikeDialog(
            bikeName = bike.name,
            isBusy = isBusy(state),
            onConfirm = {
                pendingDelete = null
                viewModel.deleteBike(bike.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun GarageContent(
    state: GarageUiState,
    locale: Locale,
    onBack: () -> Unit,
    onSelectBike: (String) -> Unit,
    onAddBike: () -> Unit,
    onRenameBike: (String) -> Unit,
    onArchiveBike: (String, Boolean) -> Unit,
    onDeleteBike: (Bike) -> Unit,
    onAddComponent: (String) -> Unit,
    onLogService: (String) -> Unit,
    onRetireComponent: (String) -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
    ) {
        GarageHeader(onBack = onBack)
        when (state) {
            GarageUiState.Loading -> Message(LOADING_TEXT)
            is GarageUiState.Empty -> EmptyGarage(state = state, onAddBike = onAddBike)
            is GarageUiState.Error ->
                StatusBanner(
                    text = state.message,
                    tone = StatusTone.DANGER,
                    modifier = Modifier.fillMaxWidth(),
                )
            is GarageUiState.Content ->
                GarageBody(
                    state = state,
                    locale = locale,
                    onSelectBike = onSelectBike,
                    onAddBike = onAddBike,
                    onRenameBike = onRenameBike,
                    onArchiveBike = onArchiveBike,
                    onDeleteBike = onDeleteBike,
                    onAddComponent = onAddComponent,
                    onLogService = onLogService,
                    onRetireComponent = onRetireComponent,
                )
        }
    }
}

@Composable
private fun GarageHeader(onBack: () -> Unit) {
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
private fun EmptyGarage(
    state: GarageUiState.Empty,
    onAddBike: () -> Unit,
) {
    state.message?.let { message ->
        StatusBanner(
            text = message,
            tone = StatusTone.INFO,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Message(EMPTY_TEXT)
    BuzzButton(
        text = ADD_BIKE_LABEL,
        onClick = onAddBike,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isBusy,
    )
}

@Composable
private fun GarageBody(
    state: GarageUiState.Content,
    locale: Locale,
    onSelectBike: (String) -> Unit,
    onAddBike: () -> Unit,
    onRenameBike: (String) -> Unit,
    onArchiveBike: (String, Boolean) -> Unit,
    onDeleteBike: (Bike) -> Unit,
    onAddComponent: (String) -> Unit,
    onLogService: (String) -> Unit,
    onRetireComponent: (String) -> Unit,
) {
    val worst = state.bikes.mapNotNull { it.alertStatus }.maxOrNull()
    if (worst != null) {
        StatusBanner(
            text = if (worst == WearStatus.DUE) DUE_ALERT_TEXT else APPROACHING_ALERT_TEXT,
            tone = if (worst == WearStatus.DUE) StatusTone.DANGER else StatusTone.WARNING,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.message?.let { message ->
        StatusBanner(
            text = message,
            tone = StatusTone.INFO,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    BuzzButton(
        text = ADD_BIKE_LABEL,
        onClick = onAddBike,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isBusy,
    )
    state.bikes.forEach { summary ->
        BikeCard(
            summary = summary,
            selected = summary.bike.id == state.selectedBikeId,
            isBusy = state.isBusy,
            onSelect = { onSelectBike(summary.bike.id) },
            onRename = { onRenameBike(summary.bike.id) },
            onArchive = { onArchiveBike(summary.bike.id, !summary.bike.isArchived) },
            onDelete = { onDeleteBike(summary.bike) },
        )
    }
    BikeDetailSection(
        selected = state.selected,
        locale = locale,
        isBusy = state.isBusy,
        onAddComponent = onAddComponent,
        onLogService = onLogService,
        onRetireComponent = onRetireComponent,
    )
}

@Composable
private fun BikeCard(
    summary: BikeWearSummary,
    selected: Boolean,
    isBusy: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BuzzTheme.colors
    val bike = summary.bike
    val description =
        buildString {
            append(bike.name)
            if (bike.isArchived) append(", archivada")
            append(". ")
            append(if (selected) "Bici seleccionada." else "Tocar para ver sus componentes.")
            summary.alertStatus?.let { append(" ${GarageFormat.alertText(it)}.") }
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH_DP.dp)
                .background(colors.panel)
                .border(BuzzDimens.Border, if (selected) colors.accent else colors.ink)
                .clickable(role = Role.Button, onClick = onSelect)
                .semantics { contentDescription = description }
                .padding(CARD_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TEXT_GAP_DP.dp),
    ) {
        Text(
            text = bike.name,
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
        )
        if (selected) {
            Text(
                text = SELECTED_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
            )
        }
        if (bike.isArchived) {
            Text(
                text = ARCHIVED_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
            )
        }
        summary.alertStatus?.let { status ->
            Text(
                text = GarageFormat.alertText(status),
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
            )
        }
        BuzzButton(
            text = RENAME_LABEL,
            onClick = onRename,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy,
            primary = false,
        )
        BuzzButton(
            text = if (bike.isArchived) RESTORE_LABEL else ARCHIVE_LABEL,
            onClick = onArchive,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy,
            primary = false,
        )
        BuzzButton(
            text = DELETE_LABEL,
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy,
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

private fun isBusy(state: GarageUiState): Boolean =
    when (state) {
        is GarageUiState.Empty -> state.isBusy
        is GarageUiState.Content -> state.isBusy
        GarageUiState.Loading, is GarageUiState.Error -> false
    }
