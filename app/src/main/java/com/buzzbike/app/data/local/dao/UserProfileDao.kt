package com.buzzbike.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.buzzbike.app.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Persistence operations for the singleton body profile.
 *
 * Every read and write is pinned to [UserProfileEntity.SINGLE_ROW_ID], so the
 * table can never hold more than one row through this contract.
 */
@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1 LIMIT 1")
    fun observe(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 1 LIMIT 1")
    suspend fun get(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)

    @Query("DELETE FROM user_profile")
    suspend fun clear()
}
