package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.ride.session.GpsStatus

/** Outcome of the system location permission dialog, as seen by the UI. */
enum class LocationPermissionRequestResult { NOT_REQUESTED, GRANTED, APPROXIMATE, DENIED }

/** Presentation model for the location permission prompt on the ride screen. */
data class LocationPermissionUiModel(
    val showGrantAction: Boolean,
    val grantActionLabel: String,
    val message: String?,
)

/**
 * Pure presentation logic for the runtime location permission prompt.
 *
 * Maps the domain [GpsStatus] plus the last dialog outcome to the banner copy
 * and the visibility of the grant action. It has no Android imports, so it is
 * testable on the JVM without Robolectric.
 */
object LocationPermissionUi {
    private const val GRANT_LABEL = "DAR PERMISO"
    private const val MISSING_PERMISSION_MESSAGE = "Falta permiso de ubicación para grabar."
    private const val DENIED_PERMISSION_MESSAGE = "Sin permiso de ubicación no se puede grabar un ride."
    private const val APPROXIMATE_PERMISSION_MESSAGE =
        "Con ubicación aproximada no se puede grabar. Elegí «Precisa» en el permiso."

    /**
     * Maps the grants returned by the system dialog to a UI outcome.
     *
     * The domain requires FINE location ([GpsStatus.NO_PERMISSION] is derived
     * from it), so coarse-only must not be treated as granted: it degrades to
     * [LocationPermissionRequestResult.APPROXIMATE] so the banner can tell the
     * user to pick "Precisa" instead of showing misleading copy.
     */
    fun classify(
        fineGranted: Boolean,
        coarseGranted: Boolean,
    ): LocationPermissionRequestResult =
        when {
            fineGranted -> LocationPermissionRequestResult.GRANTED
            coarseGranted -> LocationPermissionRequestResult.APPROXIMATE
            else -> LocationPermissionRequestResult.DENIED
        }

    fun resolve(
        gps: GpsStatus,
        result: LocationPermissionRequestResult,
    ): LocationPermissionUiModel =
        when (gps) {
            GpsStatus.NO_PERMISSION ->
                when (result) {
                    // GRANTED here is defensive: the session refresh has not
                    // propagated yet, so the initial copy still applies.
                    LocationPermissionRequestResult.NOT_REQUESTED,
                    LocationPermissionRequestResult.GRANTED,
                    ->
                        LocationPermissionUiModel(
                            showGrantAction = true,
                            grantActionLabel = GRANT_LABEL,
                            message = MISSING_PERMISSION_MESSAGE,
                        )
                    LocationPermissionRequestResult.DENIED ->
                        LocationPermissionUiModel(
                            showGrantAction = true,
                            grantActionLabel = GRANT_LABEL,
                            message = DENIED_PERMISSION_MESSAGE,
                        )
                    LocationPermissionRequestResult.APPROXIMATE ->
                        LocationPermissionUiModel(
                            showGrantAction = true,
                            grantActionLabel = GRANT_LABEL,
                            message = APPROXIMATE_PERMISSION_MESSAGE,
                        )
                }
            GpsStatus.SEARCHING,
            GpsStatus.FIX,
            ->
                LocationPermissionUiModel(
                    showGrantAction = false,
                    grantActionLabel = GRANT_LABEL,
                    message = null,
                )
        }
}
