package com.buzzbike.app.ui.history

import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.history.WeeklySummary
import com.buzzbike.app.domain.history.WeeklySummaryCalculator
import java.time.ZoneId

/** UI state of the ride history screen. */
sealed interface HistoryUiState {
    data object Loading : HistoryUiState

    data class Empty(
        val weekly: WeeklySummary,
    ) : HistoryUiState

    data class Content(
        val rides: List<RideRecord>,
        val weekly: WeeklySummary,
    ) : HistoryUiState

    data class Error(
        val message: String,
    ) : HistoryUiState
}

/**
 * Builds [HistoryUiState] from an already-emitted ride list.
 *
 * The weekly strip is derived with [WeeklySummaryCalculator] using the injected
 * reference instant, so the factory stays deterministic and JVM-testable.
 */
object HistoryUiStateFactory {
    fun build(
        rides: List<RideRecord>,
        referenceMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): HistoryUiState {
        val weekly = WeeklySummaryCalculator.summarize(rides, referenceMillis, zoneId)
        return if (rides.isEmpty()) {
            HistoryUiState.Empty(weekly)
        } else {
            HistoryUiState.Content(rides = rides, weekly = weekly)
        }
    }
}
