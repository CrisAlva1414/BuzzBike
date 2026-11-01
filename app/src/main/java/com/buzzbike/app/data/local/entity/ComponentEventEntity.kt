package com.buzzbike.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One wear milestone for a [ComponentEntity] (ADR-004).
 *
 * `SERVICE` marks the accumulation baseline: usage is summed from the latest
 * `SERVICE` event (or the install date when there is none). `APPROACHING` and
 * `DUE` are alert snapshots; they never reset the baseline.
 *
 * `type` stores the event name as a string so the schema is independent of
 * enum ordinals. Deleting the component cascades its events.
 */
@Entity(
    tableName = "component_events",
    foreignKeys = [
        ForeignKey(
            entity = ComponentEntity::class,
            parentColumns = ["id"],
            childColumns = ["componentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("componentId")],
)
data class ComponentEventEntity(
    @PrimaryKey val id: String,
    val componentId: String,
    val type: String,
    val atMillis: Long,
    val wearRatioAtEvent: Double? = null,
    val consumedKm: Double? = null,
    val consumedHours: Double? = null,
    val notes: String? = null,
) {
    companion object {
        /** Component was serviced/replaced; resets accumulated usage. */
        const val TYPE_SERVICE = "SERVICE"

        /** Component crossed the 80% threshold (ADR-004:44). */
        const val TYPE_APPROACHING = "APPROACHING"

        /** Component crossed the 100% threshold (ADR-004:45). */
        const val TYPE_DUE = "DUE"
    }
}
