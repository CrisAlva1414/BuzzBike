package com.buzzbike.app.sensor.pressure

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.buzzbike.app.domain.elevation.PressureSensorPort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PressureReading(
    val pressureHpa: Double,
    val timestampNanos: Long,
)

@Singleton
class PressureSensor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PressureSensorPort {
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        fun hasBarometer(): Boolean = barometer != null

        override fun pressureUpdates(): Flow<PressureReading> =
            callbackFlow {
                val sensor = barometer
                if (sensor == null) {
                    close()
                } else {
                    val listener =
                        object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                if (event.sensor.type == Sensor.TYPE_PRESSURE && event.values.isNotEmpty()) {
                                    trySend(PressureReading(event.values[0].toDouble(), event.timestamp))
                                }
                            }

                            @Suppress("EmptyFunctionBlock")
                            override fun onAccuracyChanged(
                                sensor: Sensor,
                                accuracy: Int,
                            ) {}
                        }

                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)

                    awaitClose {
                        sensorManager.unregisterListener(listener)
                    }
                }
            }
    }
