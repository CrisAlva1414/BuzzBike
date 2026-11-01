package com.buzzbike.app.ui.history

import com.buzzbike.app.domain.history.RideDetail

/** UI state of the ride detail screen. */
sealed interface RideDetailUiState {
    data object Loading : RideDetailUiState

    /**
     * @property bikeName current garage name of the ride's assigned bike, or
     *   `null` when the ride has no bike or the bike no longer exists. The
     *   screen distinguishes those two cases with `detail.ride.bikeId` and
     *   renders "Bici eliminada" for a dangling assignment.
     */
    data class Content(
        val detail: RideDetail,
        val bikeName: String? = null,
    ) : RideDetailUiState

    data object NotFound : RideDetailUiState

    data class Error(
        val message: String,
    ) : RideDetailUiState
}

/** UI state of the GPX export action. */
sealed interface GpxExportState {
    data object Idle : GpxExportState

    data object Exporting : GpxExportState

    data object Success : GpxExportState

    data class Error(
        val message: String,
    ) : GpxExportState
}
