package com.buzzbike.app.sensor.location

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LocationReading(
    val location: Location,
    val timestampNanos: Long = System.nanoTime(),
)

@Singleton
class LocationSensor
    @Inject
    constructor(
        private val fusedLocationClient: FusedLocationProviderClient,
    ) {
        @SuppressLint("MissingPermission")
        fun locationUpdates(intervalMillis: Long = 1000L): Flow<LocationReading> =
            callbackFlow {
                val request =
                    LocationRequest
                        .Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
                        .setMinUpdateIntervalMillis(intervalMillis / 2)
                        .build()

                val callback =
                    object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            for (location in result.locations) {
                                trySend(LocationReading(location))
                            }
                        }
                    }

                fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

                awaitClose {
                    fusedLocationClient.removeLocationUpdates(callback)
                }
            }
    }
