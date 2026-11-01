package com.buzzbike.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rides")
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
)
