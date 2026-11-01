package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.UserProfileDao
import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.calories.UserProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [UserProfileStore].
 *
 * The database is encrypted as a whole by SQLCipher, so the profile inherits
 * encryption at rest with no extra handling (AGENTS.md §7).
 */
@Singleton
class ProfileRepository
    @Inject
    constructor(
        private val dao: UserProfileDao,
    ) : UserProfileStore {
        override fun observe(): Flow<UserProfile?> = dao.observe().map { it?.toUserProfile() }

        override suspend fun get(): UserProfile? = dao.get()?.toUserProfile()

        override suspend fun save(profile: UserProfile) {
            dao.upsert(profile.toEntity(updatedAtMillis = System.currentTimeMillis()))
        }

        override suspend fun clear() {
            dao.clear()
        }
    }
