package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.domain.calories.CalorieSummary
import com.buzzbike.app.domain.calories.FALLBACK_WEIGHT_KG
import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.history.RideRecord

/**
 * Maps a persisted ride into the domain history read model.
 *
 * Calories are only projected when the whole persisted band is present,
 * finite and ordered (`low <= high`). Anything else yields `null`; the mapper
 * never fills gaps with invented values (ADR-001).
 */
internal fun RideEntity.toRideRecord(): RideRecord =
    RideRecord(
        id = id,
        startTimeMillis = startTimeMillis,
        endTimeMillis = endTimeMillis,
        distanceM = distanceM,
        movingTimeSeconds = movingTimeSeconds,
        stoppedTimeSeconds = stoppedTimeSeconds,
        elevationGainM = elevationGainM,
        elevationLossM = elevationLossM,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        calories = toCalorieSummary(),
        profileSnapshot = toProfileSnapshot(),
        bikeId = bikeId,
    )

/**
 * Projects the persisted snapshot only when at least one input was stored.
 * An all-null snapshot means "ride has no profile snapshot" (legacy rides or a
 * ride recorded without a profile), not a profile with empty fields.
 */
internal fun RideEntity.toProfileSnapshot(): UserProfile? {
    val weight = profileWeightKg
    val height = profileHeightCm
    val age = profileAgeYears
    val male = profileIsMale
    val hasSnapshot = listOf(weight, height, age, male).any { it != null }
    if (!hasSnapshot) {
        return null
    }
    return UserProfile(
        weightKg = weight,
        heightCm = height,
        ageYears = age,
        isMale = male,
    )
}

/**
 * Writes the profile used by a ride into its four nullable snapshot columns.
 *
 * A `null` profile (or a profile with every input absent) clears all four
 * columns, which the read side projects as "no snapshot". Partial profiles are
 * stored field by field so an incomplete snapshot is never turned into a
 * fabricated complete profile (ADR-001 §5.3.4).
 */
internal fun RideEntity.withProfileSnapshot(profile: UserProfile?): RideEntity =
    copy(
        profileWeightKg = profile?.weightKg,
        profileHeightCm = profile?.heightCm,
        profileAgeYears = profile?.ageYears,
        profileIsMale = profile?.isMale,
    )

private fun RideEntity.toCalorieSummary(): CalorieSummary? {
    val estimate = caloriesKcal
    val low = caloriesLowKcal
    val high = caloriesHighKcal
    val model = calorieModel
    return when {
        estimate == null || low == null -> null
        high == null || model == null -> null
        else ->
            bandSummary(
                estimate = estimate,
                low = low,
                high = high,
                model = model,
                // No persisted weight means the calculator used the documented
                // 70 kg fallback; the read model keeps that fact so the UI can
                // label it (ADR-001 §5.3).
                assumedWeightKg = if (profileWeightKg == null) FALLBACK_WEIGHT_KG else null,
            )
    }
}

private fun bandSummary(
    estimate: Double,
    low: Double,
    high: Double,
    model: String,
    assumedWeightKg: Double? = null,
): CalorieSummary? {
    val finite = estimate.isFinite() && low.isFinite() && high.isFinite()
    return if (finite && low <= high) {
        CalorieSummary(
            estimateKcal = estimate,
            lowKcal = low,
            highKcal = high,
            model = model,
            assumedWeightKg = assumedWeightKg,
        )
    } else {
        null
    }
}
