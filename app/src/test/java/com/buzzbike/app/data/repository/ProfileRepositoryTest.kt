package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.UserProfileDao
import com.buzzbike.app.data.local.entity.UserProfileEntity
import com.buzzbike.app.domain.calories.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM test for [ProfileRepository] using an in-memory fake DAO. */
class ProfileRepositoryTest {
    private val dao = FakeUserProfileDao()
    private val repository = ProfileRepository(dao)

    @Test
    fun given_noRow_when_observed_then_emitsNull() =
        runTest {
            assertNull(repository.observe().first())
        }

    @Test
    fun given_storedRow_when_observed_then_mapsToDomain() =
        runTest {
            dao.stored.value =
                UserProfileEntity(
                    updatedAtMillis = 1_000L,
                    weightKg = 70.0,
                    heightCm = 175.0,
                    ageYears = 30,
                    isMale = true,
                )

            assertEquals(
                UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30, isMale = true),
                repository.observe().first(),
            )
        }

    @Test
    fun given_profile_when_saved_then_mapsToSingletonRowWithTimestamp() =
        runTest {
            repository.save(UserProfile(weightKg = 80.0, heightCm = 180.0, ageYears = 40, isMale = false))

            val stored = requireNotNull(dao.lastUpserted)
            assertEquals(UserProfileEntity.SINGLE_ROW_ID, stored.id)
            assertTrue("save must stamp the update time", stored.updatedAtMillis > 0L)
            assertEquals(80.0, stored.weightKg)
            assertEquals(180.0, stored.heightCm)
            assertEquals(40, stored.ageYears)
            assertEquals(false, stored.isMale)
        }

    @Test
    fun given_storedRow_when_get_then_mapsToDomain() =
        runTest {
            dao.stored.value = UserProfileEntity(updatedAtMillis = 1_000L, weightKg = 65.0)

            assertEquals(UserProfile(weightKg = 65.0), repository.get())
        }

    @Test
    fun given_storedRow_when_cleared_then_daoIsEmptied() =
        runTest {
            dao.stored.value = UserProfileEntity(updatedAtMillis = 1_000L, weightKg = 65.0)

            repository.clear()

            assertNull(dao.stored.value)
            assertNull(repository.observe().first())
        }

    private class FakeUserProfileDao : UserProfileDao {
        val stored = MutableStateFlow<UserProfileEntity?>(null)
        var lastUpserted: UserProfileEntity? = null

        override fun observe(): Flow<UserProfileEntity?> = stored

        override suspend fun get(): UserProfileEntity? = stored.value

        override suspend fun upsert(profile: UserProfileEntity) {
            lastUpserted = profile
            stored.value = profile
        }

        override suspend fun clear() {
            stored.value = null
        }
    }
}
