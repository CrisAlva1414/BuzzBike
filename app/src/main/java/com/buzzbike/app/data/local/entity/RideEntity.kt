package com.buzzbike.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rides",
    // Only rides assigned to a bike contribute to that bike's wear. The column
    // is a plain nullable reference with no SQL foreign key: adding a hard FK
    // to an existing table would require rebuilding `rides`, and deleting a
    // bike must never delete or block historical rides.
    indices = [Index("bike_id")],
)
data class RideEntity(
    @PrimaryKey val id: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val distanceM: Double = 0.0,
    val movingTimeSeconds: Long = 0L,
    val stoppedTimeSeconds: Long = 0L,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val caloriesKcal: Double? = null,
    val caloriesLowKcal: Double? = null,
    val caloriesHighKcal: Double? = null,
    val calorieModel: String? = null,
    // Snapshot of the profile used when this ride was calculated. Rides stored
    // before schema v5 keep all four columns null (ADR-001 §5.3.4).
    @ColumnInfo(name = "profile_weight_kg") val profileWeightKg: Double? = null,
    @ColumnInfo(name = "profile_height_cm") val profileHeightCm: Double? = null,
    @ColumnInfo(name = "profile_age_years") val profileAgeYears: Int? = null,
    @ColumnInfo(name = "profile_is_male") val profileIsMale: Boolean? = null,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val isActive: Boolean = false,
    // Added in schema v6 (U4 garage). Null means "not assigned to a bike".
    @ColumnInfo(name = "bike_id") val bikeId: String? = null,
)
