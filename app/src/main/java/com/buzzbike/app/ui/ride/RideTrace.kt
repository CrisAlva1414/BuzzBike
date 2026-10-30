package com.buzzbike.app.ui.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.buzzbike.app.domain.ride.session.LatLon
import com.buzzbike.app.ui.theme.BuzzDimens
import com.buzzbike.app.ui.theme.BuzzTheme

private const val MIN_TRACE_POINTS = 2
private const val TRACE_MARGIN = 0.08f
private const val TRACE_STROKE_DP = 3
private const val TRACE_PADDING_DP = 8
private const val MIN_SPAN = 1e-9
private const val EMPTY_TRACE_TEXT = "Esperando señal…"
private const val TRACE_DESCRIPTION = "Traza del ride en curso"

/**
 * Dibuja la polilínea de los últimos puntos GPS sin mapas ni red.
 *
 * Las coordenadas se normalizan al bounding box del propio trazo con un margen,
 * de modo que una traza corta igual ocupe el panel. Con menos de dos puntos
 * válidos se muestra un panel vacío con borde y texto de espera.
 */
@Composable
fun RideTrace(
    points: List<LatLon>,
    modifier: Modifier = Modifier,
) {
    val colors = BuzzTheme.colors
    val validPoints = points.filter { it.lat.isFinite() && it.lon.isFinite() }
    val hasTrace = validPoints.size >= MIN_TRACE_POINTS
    Box(
        modifier =
            modifier
                .background(colors.panel)
                .border(BuzzDimens.Border, colors.ink)
                .clearAndSetSemantics {
                    contentDescription = if (hasTrace) TRACE_DESCRIPTION else EMPTY_TRACE_TEXT
                },
        contentAlignment = Alignment.Center,
    ) {
        if (hasTrace) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(TRACE_PADDING_DP.dp),
            ) {
                drawPath(
                    path = buildTracePath(validPoints, size.width, size.height),
                    color = colors.ink,
                    style =
                        Stroke(
                            width = TRACE_STROKE_DP.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                )
            }
        } else {
            Text(
                text = EMPTY_TRACE_TEXT,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
            )
        }
    }
}

private fun buildTracePath(
    points: List<LatLon>,
    width: Float,
    height: Float,
): Path {
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val latSpan = (maxLat - minLat).takeIf { it > MIN_SPAN } ?: MIN_SPAN
    val lonSpan = (maxLon - minLon).takeIf { it > MIN_SPAN } ?: MIN_SPAN
    val insetX = width * TRACE_MARGIN
    val insetY = height * TRACE_MARGIN
    val drawWidth = (width - 2 * insetX).coerceAtLeast(0f)
    val drawHeight = (height - 2 * insetY).coerceAtLeast(0f)

    fun xFor(lon: Double): Float = insetX + ((lon - minLon) / lonSpan * drawWidth).toFloat()

    fun yFor(lat: Double): Float = insetY + ((maxLat - lat) / latSpan * drawHeight).toFloat()

    val path = Path()
    points.forEachIndexed { index, point ->
        val offset = Offset(xFor(point.lon), yFor(point.lat))
        if (index == 0) {
            path.moveTo(offset.x, offset.y)
        } else {
            path.lineTo(offset.x, offset.y)
        }
    }
    return path
}
