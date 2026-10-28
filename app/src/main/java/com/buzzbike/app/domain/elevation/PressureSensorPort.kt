package com.buzzbike.app.domain.elevation

import com.buzzbike.app.sensor.pressure.PressureReading
import kotlinx.coroutines.flow.Flow

/**
 * Port over the platform barometer.
 *
 * `PressureReading` is a pure data class (no Android types), so the elevation
 * use case can consume it on the JVM. The Android adapter lives in
 * `sensor/pressure` and registers the real hardware listener.
 */
interface PressureSensorPort {
    fun pressureUpdates(): Flow<PressureReading>
}
