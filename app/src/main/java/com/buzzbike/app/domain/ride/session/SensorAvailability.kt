package com.buzzbike.app.domain.ride.session

/**
 * Port over optional sensor availability.
 *
 * The Android implementation is wired in a later slice; keeping the port in
 * `domain` lets the session manager stay JVM-pure (AGENTS.md §3).
 */
interface SensorAvailability {
    fun hasBarometer(): Boolean
}

/** Safe default for devices/adapters that have not reported a barometer. */
object NoBarometerSensorAvailability : SensorAvailability {
    override fun hasBarometer(): Boolean = false
}
