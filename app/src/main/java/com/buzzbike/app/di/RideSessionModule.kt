package com.buzzbike.app.di

import com.buzzbike.app.data.repository.RideRepository
import com.buzzbike.app.domain.ride.engine.RideEngine
import com.buzzbike.app.domain.ride.session.RideSessionManager
import com.buzzbike.app.domain.ride.session.TrackingManager
import com.buzzbike.app.sensor.location.AndroidLocationPermissionChecker
import com.buzzbike.app.sensor.location.LocationSensor
import com.buzzbike.app.sensor.motion.MotionSensor
import com.buzzbike.app.sensor.pressure.PressureSensor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the domain ride-session collaborators to their Android/data adapters.
 *
 * [TrackingManager] and [RideSessionManager] are domain classes that take ports
 * as constructor parameters, so they cannot rely on constructor injection for
 * the interfaces; this module supplies the concrete implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
object RideSessionModule {
    @Provides
    @Singleton
    fun provideTrackingManager(
        locationSensor: LocationSensor,
        trackPointWriter: RideRepository,
        permissionChecker: AndroidLocationPermissionChecker,
        pressureSensor: PressureSensor,
        motionSensor: MotionSensor,
    ): TrackingManager =
        TrackingManager(
            locationSensor = locationSensor,
            trackPointWriter = trackPointWriter,
            permissionChecker = permissionChecker,
            pressureSensor = pressureSensor,
            motionSensor = motionSensor,
        )

    @Provides
    @Singleton
    fun provideRideSessionManager(
        rideEngine: RideEngine,
        trackingManager: TrackingManager,
        rideSessionWriter: RideRepository,
    ): RideSessionManager =
        RideSessionManager(
            rideEngine = rideEngine,
            trackingManager = trackingManager,
            rideSessionWriter = rideSessionWriter,
        )
}
