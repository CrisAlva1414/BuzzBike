package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.ride.session.RideSessionManager
import com.buzzbike.app.domain.ride.session.RideSessionState

/**
 * State holder for the runtime location-permission hand-off to the session.
 *
 * The permission outcome itself — permanent versus retryable denial and the
 * "ABRIR AJUSTES" action — is pure presentation and lives in
 * [LocationPermissionUi]. This coordinator owns the other half: re-evaluating
 * the manager's authoritative `Ready` state after the system dialog or the app
 * settings return, without refreshing a live ride whose metrics would be
 * clobbered.
 *
 * @param rideSessionManager source of the authoritative session state.
 */
class RidePermissionCoordinator(
    private val rideSessionManager: RideSessionManager,
) {
    /**
     * Re-evaluates [RideSessionState.Ready] after the system permission dialog returns.
     *
     * It is a no-op outside [RideSessionState.Ready]: the dialog callback can
     * arrive after a ride became active, and refreshing then would clobber the
     * live session.
     */
    fun onLocationPermissionResult() {
        refreshIfReady()
    }

    /**
     * Re-evaluates the session after returning from the permission dialog or
     * the system app settings.
     *
     * Same guard as [onLocationPermissionResult]: only a
     * [RideSessionState.Ready] session is refreshed.
     */
    fun onResume() {
        refreshIfReady()
    }

    private fun refreshIfReady() {
        if (rideSessionManager.state.value is RideSessionState.Ready) {
            rideSessionManager.refreshReady()
        }
    }
}
