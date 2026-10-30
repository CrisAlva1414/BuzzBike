package com.buzzbike.app.domain.ride.session

/**
 * Port that starts and stops the platform foreground service which keeps the
 * tracking session alive while the app is in the background.
 *
 * The domain only needs this narrow contract; the Android adapter lives in the
 * `service` package so `domain/` stays free of platform dependencies.
 */
interface RideServiceController {
    /** Starts the foreground service, if it is not already running. */
    fun start()

    /** Stops the foreground service, if it is running. */
    fun stop()
}
