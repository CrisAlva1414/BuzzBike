package com.buzzbike.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * SQLCipher ships its native library as a separate `.so`. It must be loaded
 * before Room builds the encrypted database, otherwise the first query fails
 * with `UnsatisfiedLinkError`. Doing it here guarantees the library is ready
 * for every Room access, including WorkManager-driven sync while the UI is
 * gone.
 *
 * JVM/Robolectric tests override the application class via
 * `src/test/resources/robolectric.properties`: there is no Android `.so` on
 * the JVM, so the loader is never exercised there.
 */
@HiltAndroidApp
class BuzzBikeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
    }
}
