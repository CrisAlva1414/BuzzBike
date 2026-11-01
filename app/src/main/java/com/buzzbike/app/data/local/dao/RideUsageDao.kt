package com.buzzbike.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Ride-side queries used by the garage data layer.
 *
 * They live in their own DAO instead of [RideDao] so the wear feature does not
 * widen the contract every existing ride fake has to implement.
 */
@Dao
interface RideUsageDao {
    /**
     * Sums distance and moving time for rides assigned to [bikeId] that started
     * at or after [sinceMillis].
     *
     * Only assigned rides count: a ride without a bike never affects wear. The
     * window is inclusive so a ride that started exactly at the service
     * timestamp belongs to the new baseline, not the old one.
     */
    @Query(
        "SELECT COALESCE(SUM(distanceM), 0.0) AS distanceM, " +
            "COALESCE(SUM(movingTimeSeconds), 0) AS movingTimeSeconds " +
            "FROM rides WHERE bike_id = :bikeId AND startTimeMillis >= :sinceMillis",
    )
    suspend fun usageForBikeSince(
        bikeId: String,
        sinceMillis: Long,
    ): RideUsageSum

    /** Assigns a ride to a bike, or clears the assignment when [bikeId] is null. */
    @Query("UPDATE rides SET bike_id = :bikeId WHERE id = :rideId")
    suspend fun assignRideToBike(
        rideId: String,
        bikeId: String?,
    )
}
