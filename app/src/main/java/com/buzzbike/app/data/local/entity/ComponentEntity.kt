package com.buzzbike.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A wearable component installed on a [BikeEntity] (ADR-004).
 *
 * `type` stores the [com.buzzbike.app.domain.wear.WearType] name as a string so
 * the schema does not depend on Kotlin enum ordinal positions. A retired
 * component keeps its row (and its event history) but is excluded from the
 * active-wear queries via `retiredAtMillis`.
 *
 * Deleting a bike cascades its components.
 */
@Entity(
    tableName = "components",
    foreignKeys = [
        ForeignKey(
            entity = BikeEntity::class,
            parentColumns = ["id"],
            childColumns = ["bikeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bikeId")],
)
data class ComponentEntity(
    @PrimaryKey val id: String,
    val bikeId: String,
    val type: String,
    val label: String,
    val installedAtMillis: Long,
    val lifecycleKm: Double? = null,
    val lifecycleHours: Double? = null,
    val lifecycleDays: Double? = null,
    val retiredAtMillis: Long? = null,
    val notes: String? = null,
)
