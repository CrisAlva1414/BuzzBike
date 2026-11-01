package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.entity.UserProfileEntity
import com.buzzbike.app.domain.calories.UserProfile

/** Maps the persisted singleton profile to the domain model. */
internal fun UserProfileEntity.toUserProfile(): UserProfile =
    UserProfile(
        weightKg = weightKg,
        heightCm = heightCm,
        ageYears = ageYears,
        isMale = isMale,
    )

/**
 * Maps the domain profile to its persisted row.
 *
 * The id is always [UserProfileEntity.SINGLE_ROW_ID] so the table stays a
 * singleton; [updatedAtMillis] is supplied by the caller to keep this function
 * pure.
 */
internal fun UserProfile.toEntity(updatedAtMillis: Long): UserProfileEntity =
    UserProfileEntity(
        id = UserProfileEntity.SINGLE_ROW_ID,
        updatedAtMillis = updatedAtMillis,
        weightKg = weightKg,
        heightCm = heightCm,
        ageYears = ageYears,
        isMale = isMale,
    )
