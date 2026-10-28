package com.buzzbike.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_points",
    indices = [Index("rideId"), Index("timestampNanos")],
)
data class TrackPointEntity(
    @PrimaryKey val id: String,
    val rideId: String,
    val timestampNanos: Long,
    val lat: Double,
    val lon: Double,
    val altitudeMslM: Double? = null,
    val altitudeEllipsoidM: Double? = null,
    val accuracyH: Double? = null,
    val accuracyV: Double? = null,
    val speedMps: Double? = null,
    val bearing: Float? = null,
    val pressureHpa: Double? = null,
)
