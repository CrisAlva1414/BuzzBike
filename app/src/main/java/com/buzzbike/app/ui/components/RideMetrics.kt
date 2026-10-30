package com.buzzbike.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buzzbike.app.domain.calories.CalorieSummary
import com.buzzbike.app.ui.ride.RideFormat
import java.util.Locale

private const val METRIC_COLUMNS = 2
private const val GRID_GAP_DP = 8

/** One label/value pair rendered by [MetricGrid]. */
data class MetricCellData(
    val label: String,
    val value: String,
)

/** Canonical Spanish labels for the ride metric cells. */
object RideMetricLabels {
    const val DISTANCE = "Distancia"
    const val MOVING_TIME = "En movimiento"
    const val STOPPED_TIME = "Detenido"
    const val ELEVATION = "Desnivel"
    const val ELEVATION_GAIN = "Desnivel +"
    const val ELEVATION_LOSS = "Desnivel −"
    const val AVG_SPEED = "Velocidad media"
    const val MAX_SPEED = "Velocidad máxima"
    const val CALORIES = "Calorías"
}

/**
 * Two-column grid of metric cells with the same neobrutalist layout used during
 * a live ride and on the finished summary.
 */
@Composable
fun MetricGrid(
    cells: List<MetricCellData>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
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

/**
 * Builds the calories cell using the uncertainty band and model from ADR-001
 * (AGENTS.md §6.4). If no usable estimate exists, the cell degrades to
 * [RideFormat.UNKNOWN].
 */
fun calorieMetricCell(
    calories: CalorieSummary?,
    hasProfile: Boolean,
    locale: Locale,
): MetricCellData {
    val band = RideFormat.caloriesBand(calories, hasProfile, locale)
    return if (band == null) {
        MetricCellData(RideMetricLabels.CALORIES, RideFormat.UNKNOWN)
    } else {
        MetricCellData("${RideMetricLabels.CALORIES} · ${band.model}", band.value)
    }
}
