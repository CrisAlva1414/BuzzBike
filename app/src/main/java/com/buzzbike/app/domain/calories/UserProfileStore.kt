package com.buzzbike.app.domain.calories

import kotlinx.coroutines.flow.Flow

/**
 * Port that owns the optional body profile used by the calorie cascade.
 *
 * The profile is local-only (AGENTS.md §7) and editable/deletable; clearing it
 * must keep historical rides intact because they retain their own snapshot
 * (ADR-001 §5.3.4). The data layer provides the persistence implementation.
 */
interface UserProfileStore {
    /** Emits the current profile, or `null` when the user has not saved one. */
    fun observe(): Flow<UserProfile?>

    /** One-shot read used when a ride must capture its profile snapshot. */
    suspend fun get(): UserProfile?

    /** Creates or replaces the profile. */
    suspend fun save(profile: UserProfile)

    /** Deletes the profile, falling back to the no-profile calorie band. */
    suspend fun clear()
}
