package com.buzzbike.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Body profile used by the calorie engine (ADR-001 §5.1).
 *
 * The table is a singleton: every write targets [SINGLE_ROW_ID] and
 * [com.buzzbike.app.data.local.dao.UserProfileDao.upsert] replaces that row.
 * Columns are nullable because the profile is optional and may be partial;
 * deleting it must fall back to the no-profile calorie band without touching
 * historical rides, which keep their own snapshot (ADR-001 §5.3.4).
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "weight_kg") val weightKg: Double? = null,
    @ColumnInfo(name = "height_cm") val heightCm: Double? = null,
    @ColumnInfo(name = "age_years") val ageYears: Int? = null,
    @ColumnInfo(name = "is_male") val isMale: Boolean? = null,
) {
    companion object {
        /** Fixed primary key: the profile table is a singleton by design. */
        const val SINGLE_ROW_ID = 1
    }
}
