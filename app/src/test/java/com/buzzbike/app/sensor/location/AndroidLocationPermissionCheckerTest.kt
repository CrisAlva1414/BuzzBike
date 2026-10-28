package com.buzzbike.app.sensor.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure grant decision in [AndroidLocationPermissionChecker].
 *
 * The `ContextCompat.checkSelfPermission` query needs an Android runtime and
 * is therefore only verifiable with instrumentation tests; this suite covers
 * the mapping from permission result to boolean.
 */
class AndroidLocationPermissionCheckerTest {
    @Test
    fun given_permissionGranted_when_isGranted_thenTrue() {
        assertTrue(AndroidLocationPermissionChecker.isGranted(GRANTED))
    }

    @Test
    fun given_permissionDenied_when_isGranted_thenFalse() {
        assertFalse(AndroidLocationPermissionChecker.isGranted(DENIED))
    }

    private companion object {
        // Mirrors PackageManager.PERMISSION_GRANTED / a denial result without
        // loading Android constants in a JVM test.
        private const val GRANTED = 0
        private const val DENIED = -1
    }
}
