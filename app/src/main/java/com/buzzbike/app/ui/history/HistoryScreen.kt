package com.buzzbike.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.history.WeeklySummary
import com.buzzbike.app.ui.components.BuzzButton
import com.buzzbike.app.ui.components.BuzzDisplay
import com.buzzbike.app.ui.components.StatusBanner
import com.buzzbike.app.ui.components.StatusTone
import com.buzzbike.app.ui.theme.BuzzDimens
import com.buzzbike.app.ui.theme.BuzzTheme
import java.util.Locale

private const val CONTENT_PADDING_DP = 16
private const val ROW_PADDING_DP = 12
private const val ROW_GAP_DP = 12
private const val ROW_TEXT_GAP_DP = 4
private const val SECTION_GAP_DP = 16
private const val SCREEN_TITLE = "HISTORIAL"
private const val BACK_LABEL = "VOLVER"
private const val LOADING_TEXT = "Cargando…"
private const val EMPTY_TEXT = "Todavía no hay rides guardados."

/**
 * History list of completed rides.
 *
 * Each row opens the ride detail screen through [onOpenRide]. The header keeps
 * the same neobrutalist language as the rest of the app, and the weekly strip
 * is always visible once data exists.
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenRide: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    HistoryContent(
        state = state,
        locale = LocalConfiguration.current.locales[0],
        onBack = onBack,
        onOpenRide = onOpenRide,
    )
}

@Composable
private fun HistoryContent(
    state: HistoryUiState,
    locale: Locale,
    onBack: () -> Unit,
    onOpenRide: (String) -> Unit,
) {
    val colors = BuzzTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.ground)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(CONTENT_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
    ) {
        HistoryHeader(onBack = onBack)
        when (state) {
            is HistoryUiState.Loading ->
                Text(
                    text = LOADING_TEXT,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink,
                )
            is HistoryUiState.Empty -> {
                WeeklyBanner(summary = state.weekly, locale = locale)
                Text(
                    text = EMPTY_TEXT,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink,
                )
            }
            is HistoryUiState.Content -> {
                WeeklyBanner(summary = state.weekly, locale = locale)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
                ) {
                    items(state.rides, key = { it.id }) { ride ->
                        HistoryRow(ride = ride, locale = locale, onOpenRide = onOpenRide)
                    }
                }
            }
            is HistoryUiState.Error ->
                StatusBanner(
                    text = state.message,
                    tone = StatusTone.DANGER,
                    modifier = Modifier.fillMaxWidth(),
                )
        }
    }
}

@Composable
private fun HistoryHeader(onBack: () -> Unit) {
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
private fun WeeklyBanner(
    summary: WeeklySummary,
    locale: Locale,
) {
    StatusBanner(
        text = RideHistoryFormat.weeklyLine(summary, locale),
        tone = StatusTone.INFO,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HistoryRow(
    ride: RideRecord,
    locale: Locale,
    onOpenRide: (String) -> Unit,
) {
    val colors = BuzzTheme.colors
    val title = RideHistoryFormat.rideTitle(ride.startTimeMillis)
    val subtitle = RideHistoryFormat.rowSubtitle(ride, locale)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.panel)
                .border(BuzzDimens.Border, colors.ink)
                .clickable(role = Role.Button) { onOpenRide(ride.id) }
                .semantics { contentDescription = "$title, $subtitle. Abrir detalle" }
                .padding(ROW_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_TEXT_GAP_DP.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
    }
}
