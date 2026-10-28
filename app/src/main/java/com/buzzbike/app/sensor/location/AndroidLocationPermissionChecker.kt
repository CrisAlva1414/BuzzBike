package com.buzzbike.app.sensor.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.buzzbike.app.domain.ride.session.LocationPermissionChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation backed by [ContextCompat.checkSelfPermission].
 *
 * [isGranted] is a pure seam so the decision logic can be unit-tested on the
 * JVM; the actual permission query requires an Android runtime and is covered
 * by instrumentation tests.
 */
@Singleton
class AndroidLocationPermissionChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LocationPermissionChecker {
        override fun hasFineLocationPermission(): Boolean =
            isGranted(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )

        internal companion object {
            internal fun isGranted(permissionResult: Int): Boolean =
                permissionResult == PackageManager.PERMISSION_GRANTED
        }
    }
