package com.buzzbike.app.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.domain.export.GpxExporter
import com.buzzbike.app.domain.export.GpxRide
import com.buzzbike.app.domain.history.RideDetailSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Detail-screen state holder: loads one completed ride and exports it as GPX.
 *
 * The ride id comes from the navigation `SavedStateHandle`. An unknown id, an
 * in-progress ride and a failed read all land in non-crashing states with a
 * static message. Export errors are also static: the exception is kept inside
 * the exporter's [Result], never surfaced to the UI or logged (AGENTS.md §7).
 */
@HiltViewModel
class RideDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val source: RideDetailSource,
        private val exporter: GpxExporter,
    ) : ViewModel() {
        val rideId: String? = savedStateHandle[RIDE_ID_KEY]

        private val _state = MutableStateFlow<RideDetailUiState>(RideDetailUiState.Loading)
        val state: StateFlow<RideDetailUiState> = _state.asStateFlow()

        private val _exportState = MutableStateFlow<GpxExportState>(GpxExportState.Idle)
        val exportState: StateFlow<GpxExportState> = _exportState.asStateFlow()

        init {
            viewModelScope.launch {
                load()
            }
        }

        /** Reloads the ride from the source, mapping every outcome to a state. */
        private suspend fun load() {
            val id = rideId
            if (id == null) {
                _state.value = RideDetailUiState.NotFound
                return
            }
            _state.value = RideDetailUiState.Loading
            _state.value =
                runCatching { source.getRideDetail(id) }
                    .fold(
                        onSuccess = { detail ->
                            if (detail == null) {
                                RideDetailUiState.NotFound
                            } else {
                                RideDetailUiState.Content(detail)
                            }
                        },
                        onFailure = { RideDetailUiState.Error(LOAD_ERROR_MESSAGE) },
                    )
        }

        /**
         * Hands the loaded ride to the exporter for [destination].
         *
         * Does nothing unless the screen is showing [RideDetailUiState.Content],
         * so an export can never race the initial load or run for a missing
         * ride. Serialization lives in the exporter, keeping this method thin
         * and the CPU-bound XML build off the main thread.
         */
        fun exportGpx(destination: String) {
            val detail = (_state.value as? RideDetailUiState.Content)?.detail ?: return
            _exportState.value = GpxExportState.Exporting
            val ride =
                GpxRide(
                    name = RideHistoryFormat.rideTitle(detail.ride.startTimeMillis),
                    startTimeMillis = detail.ride.startTimeMillis,
                    points = detail.points,
                )
            viewModelScope.launch {
                _exportState.value =
                    if (exporter.export(destination, ride).isSuccess) {
                        GpxExportState.Success
                    } else {
                        GpxExportState.Error(EXPORT_ERROR_MESSAGE)
                    }
            }
        }

        /** Clears a previous [GpxExportState.Success] or [GpxExportState.Error]. */
        fun resetExportState() {
            _exportState.value = GpxExportState.Idle
        }

        private companion object {
            const val RIDE_ID_KEY = "rideId"
            const val LOAD_ERROR_MESSAGE = "No se pudo cargar el ride."
            const val EXPORT_ERROR_MESSAGE = "No se pudo exportar el GPX."
        }
    }
