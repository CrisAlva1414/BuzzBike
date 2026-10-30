package com.buzzbike.app.ui.history

import com.buzzbike.app.domain.history.RideDetail

/** UI state of the ride detail screen. */
sealed interface RideDetailUiState {
    data object Loading : RideDetailUiState

    data class Content(
        val detail: RideDetail,
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
