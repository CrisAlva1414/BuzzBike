package com.buzzbike.app.sensor.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.buzzbike.app.domain.ride.session.MotionSensorPort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private const val ACCELEROMETER_AXIS_COUNT = 3

data class MotionReading(
    val accelMagnitudeMps2: Double,
    val timestampNanos: Long,
)

@Singleton
class MotionSensor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MotionSensorPort {
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        override fun motionUpdates(): Flow<MotionReading> =
            callbackFlow {
                val sensor = accelerometer
                if (sensor == null) {
                    close()
                } else {
                    val listener =
                        object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER &&
                                    event.values.size >= ACCELEROMETER_AXIS_COUNT
                                ) {
                                    val x = event.values[0].toDouble()
                                    val y = event.values[1].toDouble()
                                    val z = event.values[2].toDouble()
                                    val magnitude = sqrt(x * x + y * y + z * z)
                                    trySend(MotionReading(magnitude, event.timestamp))
                                }
                            }

                            @Suppress("EmptyFunctionBlock")
                            override fun onAccuracyChanged(
                                sensor: Sensor,
                                accuracy: Int,
                            ) {}
                        }

                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)

                    awaitClose {
                        sensorManager.unregisterListener(listener)
                    }
                }
            }
    }
