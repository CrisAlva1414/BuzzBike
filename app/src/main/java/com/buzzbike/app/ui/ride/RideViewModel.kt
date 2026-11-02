package com.buzzbike.app.ui.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.domain.calories.UserProfileStore
import com.buzzbike.app.domain.ride.session.RideServiceController
import com.buzzbike.app.domain.ride.session.RideSessionManager
import com.buzzbike.app.domain.ride.session.RideSessionState
import com.buzzbike.app.domain.sync.RideSyncCoordinator
import com.buzzbike.app.domain.wear.BikeRepository
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
 * Optional Hermes sync is off by default (AGENTS.md §7): the coordinator
 * re-reads the persisted configuration and secret under the shared mutation
 * gate when a ride stops.
 */
@HiltViewModel
class RideViewModel
    @Inject
    constructor(
        private val rideSessionManager: RideSessionManager,
        private val rideSyncCoordinator: RideSyncCoordinator,
        private val rideServiceController: RideServiceController,
        private val profileStore: UserProfileStore,
        bikeRepository: BikeRepository,
    ) : ViewModel() {
        val state: StateFlow<RideSessionState> =
            rideSessionManager.state.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = RideSessionState.Idle,
            )

        /**
         * Owns the garage flow, the current selection and its validation.
         * Extracted so this view model stays a thin session adapter.
         */
        private val bikeSelector = RideBikeSelector(bikeRepository, viewModelScope)

        /** Selector state shown in [RideSessionState.Ready]. */
        val bikeSelection: StateFlow<RideBikeSelection> = bikeSelector.state

        /**
         * Owns the permission re-evaluation after the dialog or app settings.
         * Extracted so this view model stays a thin session adapter.
         */
        val permission = RidePermissionCoordinator(rideSessionManager)

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

        /**
         * Starts a ride with the current garage selection.
         *
         * Both the profile and the bike are read synchronously and fixed for
         * this ride: editing the garage or the profile afterwards cannot change
         * what was persisted. While the request is in flight
         * [RideBikeSelection.isBusy] disables the selector.
         */
        fun startRide() {
            if (startInFlight) return
            startInFlight = true
            val bikeId = bikeSelector.beginStart()
            viewModelScope.launch {
                try {
                    // Read once at start: the manager fixes this snapshot for
                    // the whole ride, so a later profile edit cannot rewrite
                    // the live values or the persisted ride. The bike was
                    // captured synchronously above for the same reason.
                    val profile = profileStore.get()
                    val rideId = rideSessionManager.startRide(profile, bikeId)
                    if (rideId != null) {
                        rideServiceController.start()
                    }
                } finally {
                    bikeSelector.endStart()
                    startInFlight = false
                }
            }
        }

        /**
         * Selects the bike for the next ride, or "Sin bici" when [bikeId] is
         * null. Ignores changes while a start is in flight and ids that are no
         * longer in the active garage.
         */
        fun selectBike(bikeId: String?) {
            bikeSelector.select(bikeId)
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
                    rideSyncCoordinator.onRideFinished(rideId)
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
                // No live profile read here: the manager recovers the snapshot
                // persisted when the ride started (ADR-007 §3).
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
