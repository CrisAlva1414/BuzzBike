package com.buzzbike.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.domain.history.RideHistorySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Exposes the history list as a hot [StateFlow].
 *
 * The source is Room-backed and already emits off the main thread, so there is
 * no extra `flowOn`. A read failure degrades to [HistoryUiState.Error] with a
 * user-facing message instead of crashing the screen.
 */
@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        source: RideHistorySource,
    ) : ViewModel() {
        val state: StateFlow<HistoryUiState> =
            source
                .observeCompletedRides()
                .map { rides ->
                    HistoryUiStateFactory.build(rides, System.currentTimeMillis())
                }.catch { emit(HistoryUiState.Error(ERROR_MESSAGE)) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                    initialValue = HistoryUiState.Loading,
                )
    }

private const val ERROR_MESSAGE = "No se pudo cargar el historial."
private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
