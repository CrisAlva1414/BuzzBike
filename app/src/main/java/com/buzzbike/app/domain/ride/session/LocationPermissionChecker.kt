package com.buzzbike.app.domain.ride.session

/**
 * Port over the runtime location permission check.
 *
 * The Android implementation lives in `sensor/location`; keeping this
 * interface in `domain` (free of Android and data types) allows the tracking
 * use case to be tested on the JVM and preserves `domain → data` inversion
 * (AGENTS.md §3).
 */
interface LocationPermissionChecker {
    fun hasFineLocationPermission(): Boolean
}
