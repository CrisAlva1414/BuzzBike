package com.buzzbike.app.ui.ride

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the system app-details screen where the user can re-grant the location
 * permission after a permanent denial.
 *
 * Some OEM builds ship no activity for [Settings.ACTION_APPLICATION_DETAILS_SETTINGS],
 * and `startActivity` then throws [ActivityNotFoundException]. Crashing the ride
 * screen would leave the user worse off than a no-op, so the missing handler is
 * swallowed here; the button simply does nothing on those devices.
 */
internal fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    } catch (ignored: ActivityNotFoundException) {
        // No safe in-app fallback exists for the system permission toggle.
    }
}
