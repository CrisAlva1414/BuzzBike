package com.buzzbike.app.ui.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.domain.ride.session.RideServiceController
import com.buzzbike.app.domain.ride.session.RideSessionManager
import com.buzzbike.app.domain.ride.session.RideSessionState
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.RideSyncCoordinator
import com.buzzbike.app.domain.sync.SyncConfigStore
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
 * `null` and the persisted [SyncConfigStore] is consulted on stop. A call to
 * [configureSync] overrides the store for tests or future per-ride toggles.
 */
@HiltViewModel
class RideViewModel
    @Inject
    constructor(
        private val rideSessionManager: RideSessionManager,
        private val rideSyncCoordinator: RideSyncCoordinator,
        private val syncConfigStore: SyncConfigStore,
        private val rideServiceController: RideServiceController,
    ) : ViewModel() {
        val state: StateFlow<RideSessionState> =
            rideSessionManager.state.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = RideSessionState.Idle,
            )

        private var syncConfig: HermesConfig? = null

        /**
         * Synchronous guard against a double stop: the first tap flips it
         * before the coroutine is launched, so a second tap in the same frame
         * cannot stop the session (or enqueue the ride) twice.
         */
        private var stopInFlight = false

        /**
         * Synchronous guard against a double start: the first tap flips it
         * before the coroutine is launched, so a second tap in the same frame
         * cannot dispatch a second `startRide`.
         */
        private var startInFlight = false

        init {
            viewModelScope.launch {
                rideSessionManager.restore()
            }
        }

        /** Enables or disables sync for subsequent rides; `null` means off. */
        fun configureSync(config: HermesConfig?) {
            syncConfig = config
        }

        fun startRide() {
            if (startInFlight) return
            startInFlight = true
            viewModelScope.launch {
                try {
                    val rideId = rideSessionManager.startRide()
                    if (rideId != null) {
                        rideServiceController.start()
                    }
                } finally {
                    startInFlight = false
                }
            }
        }

        fun pauseRide() {
            viewModelScope.launch {
                rideSessionManager.pauseRide()
            }
        }

        fun resumeRide() {
            viewModelScope.launch {
                rideSessionManager.resumeRide()
            }
        }

        fun stopRide() {
            val rideId = activeRideId() ?: return
            if (stopInFlight) return
            stopInFlight = true
            viewModelScope.launch {
                try {
                    rideSessionManager.stopRide()
                    val config = syncConfig ?: syncConfigStore.current()
                    if (config != null) {
                        rideSyncCoordinator.onRideFinished(rideId, config)
                    }
                } finally {
                    stopInFlight = false
                    rideServiceController.stop()
                }
            }
        }

        fun discardRide() {
            viewModelScope.launch {
                try {
                    rideSessionManager.discardRide()
                } finally {
                    rideServiceController.stop()
                }
            }
        }

        fun acknowledgeFinished() {
            rideSessionManager.acknowledgeFinished()
        }

        /**
         * Closes an interrupted ride: the manager processes the persisted
         * points and publishes [RideSessionState.Finished]. The foreground
         * service is not started because the ride is ending, not recording.
         */
        fun salvageRecoverableRide() {
            viewModelScope.launch {
                rideSessionManager.salvageRecoverableRide()
            }
        }

        private fun activeRideId(): String? =
            when (val current = state.value) {
                is RideSessionState.Recording -> current.rideId
                is RideSessionState.Paused -> current.rideId
                else -> null
            }
    }
