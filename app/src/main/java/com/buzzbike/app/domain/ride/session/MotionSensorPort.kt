package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.sensor.motion.MotionReading
import kotlinx.coroutines.flow.Flow

/**
 * Port over the platform accelerometer.
 *
 * `MotionReading` is a pure data class (no Android types), so the tracking
 * use case can combine it with pressure readings on the JVM. The Android
 * adapter lives in `sensor/motion`.
 */
interface MotionSensorPort {
    fun motionUpdates(): Flow<MotionReading>
}
