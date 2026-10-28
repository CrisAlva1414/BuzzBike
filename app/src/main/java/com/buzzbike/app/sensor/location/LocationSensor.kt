package com.buzzbike.app.sensor.location

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import com.buzzbike.app.domain.ride.session.LocationReading
import com.buzzbike.app.domain.ride.session.LocationSensorPort
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

@Singleton
class LocationSensor
    @Inject
    constructor(
        private val fusedLocationClient: FusedLocationProviderClient,
    ) : LocationSensorPort {
        override fun locationUpdates(): Flow<LocationReading> = locationUpdates(DEFAULT_INTERVAL_MILLIS)

        @SuppressLint("MissingPermission")
        fun locationUpdates(intervalMillis: Long): Flow<LocationReading> =
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
                                trySend(location.toReading())
                            }
                        }
                    }

                fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

                awaitClose {
                    fusedLocationClient.removeLocationUpdates(callback)
                }
            }

        private fun Location.toReading(): LocationReading =
            LocationReading(
                lat = latitude,
                lon = longitude,
                timestampNanos = System.nanoTime(),
                altitudeEllipsoidM = altitude.takeIf { hasAltitude() && it.isFinite() },
                accuracyH = accuracy.toDouble().takeIf { hasAccuracy() && it.isFinite() },
                accuracyV = verticalAccuracyMeters.toDouble().takeIf { hasVerticalAccuracy() && it.isFinite() },
                speedMps = speed.toDouble().takeIf { hasSpeed() && it.isFinite() },
                bearing = bearing.takeIf { hasBearing() && it.isFinite() },
            )

        private companion object {
            private const val DEFAULT_INTERVAL_MILLIS = 1000L
        }
    }
