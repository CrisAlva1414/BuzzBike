package com.buzzbike.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.buzzbike.app.data.local.dao.BikeDao
import com.buzzbike.app.data.local.dao.ComponentDao
import com.buzzbike.app.data.local.dao.ComponentEventDao
import com.buzzbike.app.data.local.dao.OutboxDao
import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.RideUsageDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.dao.UserProfileDao
import com.buzzbike.app.data.local.entity.BikeEntity
import com.buzzbike.app.data.local.entity.ComponentEntity
import com.buzzbike.app.data.local.entity.ComponentEventEntity
import com.buzzbike.app.data.local.entity.OutboxEntity
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity
import com.buzzbike.app.data.local.entity.UserProfileEntity

@Database(
    entities = [
        RideEntity::class,
        TrackPointEntity::class,
        OutboxEntity::class,
        UserProfileEntity::class,
        BikeEntity::class,
        ComponentEntity::class,
        ComponentEventEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    abstract fun rideUsageDao(): RideUsageDao

    abstract fun trackPointDao(): TrackPointDao

    abstract fun outboxDao(): OutboxDao

    abstract fun userProfileDao(): UserProfileDao

    abstract fun bikeDao(): BikeDao

    abstract fun componentDao(): ComponentDao

    abstract fun componentEventDao(): ComponentEventDao
}
