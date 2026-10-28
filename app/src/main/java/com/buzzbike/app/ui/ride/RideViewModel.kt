package com.buzzbike.app.ui.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.domain.ride.session.RideSessionManager
import com.buzzbike.app.domain.ride.session.RideSessionUiState
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.RideSyncCoordinator
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
 *
 * Optional Hermes sync is off by default (AGENTS.md §7): [syncConfig] starts
 * `null` and only a call to [configureSync] enables the post-ride pipeline.
 */
@HiltViewModel
class RideViewModel
    @Inject
    constructor(
        private val rideSessionManager: RideSessionManager,
        private val rideSyncCoordinator: RideSyncCoordinator,
    ) : ViewModel() {
        val uiState: StateFlow<RideSessionUiState> =
            rideSessionManager.uiState.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = RideSessionUiState(),
            )

        private var syncConfig: HermesConfig? = null

        /**
         * Synchronous guard against a double stop: the first tap flips it
         * before the coroutine is launched, so a second tap in the same frame
         * cannot stop the session (or enqueue the ride) twice.
         */
        private var stopInFlight = false

        /** Enables or disables sync for subsequent rides; `null` means off. */
        fun configureSync(config: HermesConfig?) {
            syncConfig = config
        }

        fun startRide() {
            viewModelScope.launch {
                rideSessionManager.startRide()
            }
        }

        fun stopRide() {
            if (!uiState.value.isTracking || stopInFlight) return
            stopInFlight = true
            viewModelScope.launch {
                try {
                    // Capture the id before the manager clears the active state.
                    val rideId = uiState.value.activeRideId
                    rideSessionManager.stopRide()
                    val config = syncConfig
                    if (rideId != null && config != null) {
                        rideSyncCoordinator.onRideFinished(rideId, config)
                    }
                } finally {
                    stopInFlight = false
                }
            }
        }
    }
