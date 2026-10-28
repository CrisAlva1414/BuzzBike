package com.buzzbike.app.domain.ride.session

import kotlinx.coroutines.flow.Flow

/**
 * Port over the platform location provider.
 *
 * Keeps `TrackingManager` free of Android imports and lets JVM tests feed
 * deterministic readings through a fake implementation.
 */
interface LocationSensorPort {
    fun locationUpdates(): Flow<LocationReading>
}
