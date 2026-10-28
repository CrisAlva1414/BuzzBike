package com.buzzbike.app.ui.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.domain.ride.session.RideSessionManager
import com.buzzbike.app.domain.ride.session.RideSessionUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-facing adapter over [RideSessionManager].
 *
 * The manager owns the session lifecycle and the real-time aggregate; this
 * ViewModel only scopes that state to the screen and forwards the user
 * actions, so the UI never talks to persistence directly.
 */
@HiltViewModel
class RideViewModel
    @Inject
    constructor(
        private val rideSessionManager: RideSessionManager,
    ) : ViewModel() {
        val uiState: StateFlow<RideSessionUiState> =
            rideSessionManager.uiState.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = RideSessionUiState(),
            )

        fun startRide() {
            viewModelScope.launch {
                rideSessionManager.startRide()
            }
        }

        fun stopRide() {
            viewModelScope.launch {
                rideSessionManager.stopRide()
            }
        }
    }
