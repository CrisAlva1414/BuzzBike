package com.buzzbike.app.ui.garage

import com.buzzbike.app.domain.wear.Bike
import com.buzzbike.app.domain.wear.ComponentWear
import com.buzzbike.app.domain.wear.WearStatus

/**
 * One bike in the garage, with the wear already assessed by the repository.
 *
 * [alertStatus] is the worst non-OK status among the bike's active components,
 * or `null` when everything is below the 80% threshold. It powers both the
 * garage-wide banner and the per-bike indicator.
 */
data class BikeWearSummary(
    val bike: Bike,
    val components: List<ComponentWear>,
    val alertStatus: WearStatus?,
)

/**
 * UI state of the garage screen.
 *
 * The screen is either loading, has no bikes yet, shows the garage, or failed
 * to read. [GarageUiState.Content] keeps the selected bike and an in-flight
 * flag so every mutating control can be disabled while a write is running.
 */
sealed interface GarageUiState {
    data object Loading : GarageUiState

    data class Empty(
        val isBusy: Boolean = false,
        val message: String? = null,
    ) : GarageUiState

    data class Content(
        val bikes: List<BikeWearSummary>,
        val selectedBikeId: String?,
        val isBusy: Boolean = false,
        val message: String? = null,
    ) : GarageUiState {
        /** The selected bike, or `null` while the selection converges. */
        val selected: BikeWearSummary?
            get() = bikes.firstOrNull { it.bike.id == selectedBikeId }
    }

    data class Error(
        val message: String,
    ) : GarageUiState
}

/**
 * Worst non-OK wear status of [components].
 *
 * [WearStatus] is declared from least to most severe (`OK`, `APPROACHING`,
 * `DUE`), so `maxOrNull` reports the one the user must act on first. A bike
 * with only `OK` components has no alert.
 */
internal fun worstAlertStatus(components: List<ComponentWear>): WearStatus? =
    components
        .map { it.assessment.status }
        .maxOrNull()
        ?.takeIf { it != WearStatus.OK }
