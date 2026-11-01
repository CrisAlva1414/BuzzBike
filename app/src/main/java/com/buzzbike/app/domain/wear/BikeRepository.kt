package com.buzzbike.app.domain.wear

import kotlinx.coroutines.flow.Flow

/**
 * Port that owns the bikes in the garage and which rides belong to them.
 *
 * The data layer implements it against Room. The UI depends on this interface
 * so the garage screen can be tested with an in-memory fake.
 */
interface BikeRepository {
    /** Emits every bike, archived ones last but never hidden. */
    fun observeBikes(): Flow<List<Bike>>

    suspend fun getBike(id: String): Bike?

    /** Creates a bike and returns its generated id. */
    suspend fun createBike(
        name: String,
        notes: String? = null,
    ): String

    /** Replaces the editable fields of an existing bike. */
    suspend fun updateBike(bike: Bike)

    /**
     * Deletes a bike, its components and their events.
     *
     * Its rides are kept in history with `bike_id = NULL`; they stop
     * contributing to wear but are never deleted.
     */
    suspend fun deleteBike(id: String)

    /**
     * Assigns [rideId] to [bikeId], or clears the assignment when [bikeId] is
     * null. Only rides assigned to a bike contribute to that bike's wear.
     */
    suspend fun assignRideToBike(
        rideId: String,
        bikeId: String?,
    )
}
