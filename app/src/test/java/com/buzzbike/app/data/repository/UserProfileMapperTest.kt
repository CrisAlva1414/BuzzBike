package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.entity.UserProfileEntity
import com.buzzbike.app.domain.calories.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM-pure tests for the profile entity/domain mapping. */
class UserProfileMapperTest {
    @Test
    fun given_completeEntity_when_mappedToDomain_thenFieldsArePreserved() {
        val entity =
            UserProfileEntity(
                updatedAtMillis = 1_000L,
                weightKg = 72.5,
                heightCm = 178.0,
                ageYears = 34,
                isMale = true,
            )

        assertEquals(
            UserProfile(weightKg = 72.5, heightCm = 178.0, ageYears = 34, isMale = true),
            entity.toUserProfile(),
        )
    }

    @Test
    fun given_partialEntity_when_mappedToDomain_thenNullsStayNull() {
        val entity = UserProfileEntity(updatedAtMillis = 1_000L, weightKg = 70.0)

        assertEquals(UserProfile(weightKg = 70.0), entity.toUserProfile())
    }

    @Test
    fun given_domainProfile_when_mappedToEntity_thenUsesSingletonIdAndTimestamp() {
        val entity =
            UserProfile(weightKg = 80.0, heightCm = 180.0, ageYears = 40, isMale = false)
                .toEntity(updatedAtMillis = 42L)

        assertEquals(UserProfileEntity.SINGLE_ROW_ID, entity.id)
        assertEquals(42L, entity.updatedAtMillis)
        assertEquals(80.0, entity.weightKg)
        assertEquals(180.0, entity.heightCm)
        assertEquals(40, entity.ageYears)
        assertEquals(false, entity.isMale)
    }
}
