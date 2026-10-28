package com.buzzbike.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity

@Database(
    entities = [RideEntity::class, TrackPointEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    abstract fun trackPointDao(): TrackPointDao
}
