package com.buzzbike.app.data.local.entity

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
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val isActive: Boolean = false,
)
