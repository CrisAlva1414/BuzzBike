package com.buzzbike.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.buzzbike.app.ui.theme.BuzzDimens
import com.buzzbike.app.ui.theme.BuzzTheme
import kotlinx.coroutines.launch

private const val HOLD_TO_STOP_DESCRIPTION = "Terminar ride. Mantené pulsado durante un segundo"
private const val HOLD_TO_STOP_ACTION = "Terminar ride"

/**
 * Anton va SIEMPRE en mayúsculas y compose no tiene text-transform: usá este composable
 * (o `.uppercase()`) para cualquier texto con estilo display/headline/titleLarge.
 */
@Composable
fun BuzzDisplay(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineLarge,
    textAlign: TextAlign? = null,
) {
    Text(text = text.uppercase(java.util.Locale.ROOT), modifier = modifier, style = style, textAlign = textAlign)
}

/**
 * Terminar ride SOLO con pulsación sostenida: un golpe con guantes o un bache no cierra el ride.
 * TalkBack no puede "mantener": expone la misma acción como onLongClick.
 *
 * NO COMPILADO: escrito sin toolchain. Verificar imports y firmas contra el BOM del proyecto.
 */
@Composable
fun HoldToStopButton(
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1100,
) {
    val colors = BuzzTheme.colors
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    Box(
        modifier =
            modifier
                .heightIn(min = BuzzDimens.RideActionHeight)
                .background(colors.danger)
                .drawBehind {
                    drawRect(color = colors.ink, size = Size(size.width * progress.value, size.height))
                }.border(BuzzDimens.Border, colors.ink)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = HOLD_TO_STOP_DESCRIPTION
                    onLongClick(label = HOLD_TO_STOP_ACTION) {
                        onConfirmed()
                        true
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            val job =
                                scope.launch {
                                    progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onConfirmed()
                                }
                            tryAwaitRelease()
                            job.cancel()
                            scope.launch { progress.snapTo(0f) }
                        },
                    )
                }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BuzzDisplay(
                text = "Terminar",
                style = MaterialTheme.typography.headlineSmall.copy(color = colors.onDanger),
            )
            Text(
                text = "Mantené pulsado",
                color = colors.onDanger,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Builds the single TalkBack reading of a metric cell.
 *
 * The value already carries its unit (`"12,35 km"`), so the description is
 * `"Distancia: 12,35 km"`: label first for context, then value + unit once.
 * Keep this pure so the JVM suite can assert no word is duplicated.
 */
internal fun metricCellDescription(
    label: String,
    value: String,
): String = "$label: $value"

/**
 * Celda de la rejilla 2×2 de métricas: borde sólido, sin sombra y objetivo
 * táctil mínimo. El valor va en Anton 44 sp; la etiqueta, en el cuerpo.
 *
 * [clearAndSetSemantics] drops the two child [Text] semantics so TalkBack reads
 * [metricCellDescription] exactly once instead of announcing text plus a
 * redundant `contentDescription`.
 */
@Composable
fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = BuzzTheme.colors
    Column(
        modifier =
            modifier
                .heightIn(min = BuzzDimens.MinTouch)
                .background(colors.ground)
                .border(BuzzDimens.Border, colors.ink)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clearAndSetSemantics { contentDescription = metricCellDescription(label, value) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            color = colors.ink,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
    }
}

/**
 * Botón neobrutalista: esquinas rectas, borde de 3 dp, sin sombra.
 *
 * [primary] usa el acento; el secundario usa el panel. El texto va en Anton
 * 24 sp y en mayúsculas mediante [BuzzDisplay].
 *
 * [role] defaults to [Role.Button]; tab selectors pass [Role.Tab] so TalkBack
 * announces the pressed option as a selected tab instead of a plain button.
 */
@Composable
fun BuzzButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    role: Role = Role.Button,
) {
    val colors = BuzzTheme.colors
    val background = if (primary) colors.accent else colors.panel
    val content = if (primary) colors.onAccent else colors.ink
    Box(
        modifier =
            modifier
                .heightIn(min = BuzzDimens.MinTouch)
                .background(background)
                .border(BuzzDimens.Border, colors.ink)
                .clickable(enabled = enabled, role = role, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BuzzDisplay(
            text = text,
            style = MaterialTheme.typography.titleLarge.copy(color = content),
            textAlign = TextAlign.Center,
        )
    }
}

/** Tono semántico de [StatusBanner]. */
enum class StatusTone { WARNING, DANGER, INFO }

/**
 * Visible/accessible message of a [StatusBanner].
 *
 * Pure so the JVM suite can assert the prefix is added exactly once; the
 * prefix keeps the status from being communicated by colour alone.
 */
internal fun statusBannerMessage(
    text: String,
    tone: StatusTone,
): String =
    when (tone) {
        StatusTone.WARNING -> "AVISO: $text"
        StatusTone.DANGER -> "ATENCIÓN: $text"
        StatusTone.INFO -> "INFO: $text"
    }

/**
 * Franja de estado con relleno sólido y texto contrastante.
 *
 * No comunica el estado solo con color: el texto se prefija con "AVISO",
 * "ATENCIÓN" o "INFO" y ese prefijo también va en la descripción accesible.
 * Sin animaciones.
 *
 * [clearAndSetSemantics] replaces the child [Text] semantics, so TalkBack reads
 * the banner once. When [announceChanges] is true the banner becomes a polite
 * live region (used for the map's offline notice).
 */
@Composable
fun StatusBanner(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    announceChanges: Boolean = false,
) {
    val colors = BuzzTheme.colors
    val (background, content) =
        when (tone) {
            StatusTone.WARNING -> colors.accent to colors.onAccent
            StatusTone.DANGER -> colors.danger to colors.onDanger
            StatusTone.INFO -> colors.panel to colors.ink
        }
    val message = statusBannerMessage(text, tone)
    Box(
        modifier =
            modifier
                .heightIn(min = BuzzDimens.MinTouch)
                .background(background)
                .border(BuzzDimens.Border, colors.ink)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clearAndSetSemantics {
                    contentDescription = message
                    if (announceChanges) liveRegion = LiveRegionMode.Polite
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
    }
}
