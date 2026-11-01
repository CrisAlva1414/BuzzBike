package com.buzzbike.app.ui.garage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.buzzbike.app.domain.wear.ComponentWear
import com.buzzbike.app.domain.wear.WearEngine
import com.buzzbike.app.domain.wear.WearStatus
import com.buzzbike.app.domain.wear.WearType
import com.buzzbike.app.ui.components.BuzzButton
import com.buzzbike.app.ui.components.BuzzDisplay
import com.buzzbike.app.ui.theme.BuzzDimens
import com.buzzbike.app.ui.theme.BuzzTheme
import java.util.Locale

private const val CARD_PADDING_DP = 12
private const val SECTION_GAP_DP = 16
private const val TEXT_GAP_DP = 4
private const val MIN_TOUCH_DP = 48
private const val BAR_HEIGHT_DP = 18
private const val WEAR_BAR_MAX_RATIO = 1.2
private const val ADD_COMPONENT_LABEL = "AGREGAR COMPONENTE"
private const val LOG_SERVICE_LABEL = "REGISTRAR SERVICIO"
private const val RETIRE_LABEL = "RETIRAR"
private const val SELECT_BIKE_TEXT = "Elegí una bici para ver sus componentes."
private const val NO_COMPONENTS_TEXT =
    "Esta bici no tiene componentes. Agregá uno para empezar a trackear su desgaste."
private const val NO_LIFECYCLE_HINT =
    "Sin ciclo de vida configurado: editá el componente para definir km, horas o días."

/**
 * Detail of the selected bike: components grouped by type, each with its wear
 * bar, limiting axis and accumulated usage.
 *
 * The wear bar is normalized to 0..1.2 and marks the 0.8/1.0 thresholds. Status
 * is never communicated by colour alone: the card always includes the status
 * text and percentage, and the bar's content description repeats type, status
 * and percentage for TalkBack.
 */
@Composable
fun BikeDetailSection(
    selected: BikeWearSummary?,
    locale: Locale,
    isBusy: Boolean,
    onAddComponent: (String) -> Unit,
    onLogService: (String) -> Unit,
    onRetireComponent: (String) -> Unit,
) {
    if (selected == null) {
        Message(SELECT_BIKE_TEXT)
        return
    }
    val colors = BuzzTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp)) {
        Text(
            text = selected.bike.name,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            modifier = Modifier.fillMaxWidth(),
        )
        if (selected.bike.isArchived) {
            Text(
                text = "Bici archivada",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BuzzButton(
            text = ADD_COMPONENT_LABEL,
            onClick = { onAddComponent(selected.bike.id) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy,
        )
        if (selected.components.isEmpty()) {
            Message(NO_COMPONENTS_TEXT)
        } else {
            val grouped = selected.components.groupBy { it.component.type }
            WearType.entries.forEach { type ->
                val components = grouped[type].orEmpty()
                if (components.isNotEmpty()) {
                    BuzzDisplay(
                        text = GarageFormat.typeText(type),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    components.forEach { wear ->
                        ComponentCard(
                            wear = wear,
                            locale = locale,
                            isBusy = isBusy,
                            onLogService = { onLogService(wear.component.id) },
                            onRetire = { onRetireComponent(wear.component.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentCard(
    wear: ComponentWear,
    locale: Locale,
    isBusy: Boolean,
    onLogService: () -> Unit,
    onRetire: () -> Unit,
) {
    val colors = BuzzTheme.colors
    val percent = GarageFormat.percent(wear.assessment.ratio, locale)
    val statusLine =
        if (wear.assessment.ratio == null) {
            GarageFormat.statusText(wear.assessment)
        } else {
            GarageFormat.statusText(wear.assessment) + " · " + percent
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH_DP.dp)
                .background(colors.panel)
                .border(BuzzDimens.Border, colors.ink)
                .padding(CARD_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TEXT_GAP_DP.dp),
    ) {
        Text(
            text = wear.component.label,
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
        )
        WearBar(wear = wear, locale = locale)
        Text(
            text = statusLine,
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
        )
        Text(
            text = GarageFormat.limitingDetail(wear, locale),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
        if (wear.component.lifecycle == null) {
            Text(
                text = NO_LIFECYCLE_HINT,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
            )
        }
        Text(
            text = GarageFormat.usageDetail(wear.usage, locale),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
        BuzzButton(
            text = LOG_SERVICE_LABEL,
            onClick = onLogService,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Registrar servicio de ${wear.component.label}" },
            enabled = !isBusy,
        )
        BuzzButton(
            text = RETIRE_LABEL,
            onClick = onRetire,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Retirar ${wear.component.label}" },
            enabled = !isBusy,
            primary = false,
        )
    }
}

@Composable
private fun WearBar(
    wear: ComponentWear,
    locale: Locale,
) {
    val colors = BuzzTheme.colors
    val status = wear.assessment.status
    val fillColor =
        when (status) {
            WearStatus.OK -> colors.ink
            WearStatus.APPROACHING -> colors.accent
            WearStatus.DUE -> colors.danger
        }
    val description =
        "Barra de desgaste de " +
            GarageFormat.typeText(wear.component.type) +
            ": " +
            GarageFormat.statusText(wear.assessment) +
            ", " +
            GarageFormat.percent(wear.assessment.ratio, locale)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = description },
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT_DP.dp),
        ) {
            val border = BuzzDimens.Border.toPx()
            drawRect(color = colors.panel, size = size)
            val ratio = wear.assessment.ratio ?: 0.0
            val fraction = (ratio.coerceIn(0.0, WEAR_BAR_MAX_RATIO) / WEAR_BAR_MAX_RATIO).toFloat()
            drawRect(
                color = fillColor,
                size = Size(width = size.width * fraction, height = size.height),
            )
            drawMark(WearEngine.APPROACHING_THRESHOLD, colors.ink, border)
            drawMark(WearEngine.DUE_THRESHOLD, colors.ink, border)
            drawRect(color = colors.ink, style = Stroke(width = border))
        }
    }
}

/**
 * Draws one threshold mark at [threshold] on the 0..[WEAR_BAR_MAX_RATIO] bar.
 *
 * Kept as an extension on `DrawScope` so [WearBar] stays focused on layout.
 */
private fun DrawScope.drawMark(
    threshold: Double,
    color: Color,
    strokeWidth: Float,
) {
    val x = (threshold / WEAR_BAR_MAX_RATIO * size.width).toFloat()
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = strokeWidth,
    )
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
