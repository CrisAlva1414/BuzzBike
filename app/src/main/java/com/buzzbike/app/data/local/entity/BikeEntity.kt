package com.buzzbike.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A bike in the user's garage (ADR-004).
 *
 * Bikes are user-configured containers for wearable components. Archiving is
 * a soft delete: the row keeps its components and history, but the UI can stop
 * listing it as active.
 */
@Entity(tableName = "bikes")
data class BikeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val notes: String? = null,
    val createdAtMillis: Long,
    val isArchived: Boolean = false,
)
