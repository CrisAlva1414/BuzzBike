package com.buzzbike.app.domain.calories

/**
 * Default body weight used when the user has not provided a profile.
 * ADR-001: the result is marked as an assumption and carries the wider band.
 */
private const val DEFAULT_WEIGHT_KG = 70.0

private const val SECONDS_PER_MINUTE = 60.0

/**
 * 1 MET = 3.5 mL O2 / kg / min (ACSM, cited in ADR-001).
 */
private const val OXYGEN_ML_PER_KG_PER_MIN_PER_MET = 3.5

/**
 * kcal/min = MET * 3.5 * kg / 200. The 200 folds the mL→L conversion and the
 * ~5 kcal per litre of O2 (1000 / 5), as documented in ADR-001.
 */
private const val KCAL_CONVERSION_DENOMINATOR = 200.0

private const val WITH_PROFILE_MIN_PERCENT = 20
private const val WITH_PROFILE_MAX_PERCENT = 30
private const val WITHOUT_PROFILE_MIN_PERCENT = 30
private const val WITHOUT_PROFILE_MAX_PERCENT = 40

/**
 * Calorie calculator implementing ADR-001.
 * Sample-by-sample MET integration, optional profile, uncertainty bands.
 */
class CalorieCalculator {
    fun calculate(
        speedSamplesMps: List<Double>,
        durationsSeconds: List<Double>,
        isMoving: List<Boolean>,
        profile: UserProfile = UserProfile(),
    ): CalorieResult {
        if (speedSamplesMps.isEmpty() || durationsSeconds.isEmpty()) {
            return emptyResult()
        }

        val resolvedProfile = resolveProfile(profile)
        val metMinutes =
            accumulateMetMinutes(
                speedSamplesMps = speedSamplesMps,
                durationsSeconds = durationsSeconds,
                isMoving = isMoving,
            )

        return CalorieResult(
            totalKcal = kcalFrom(metMinutes.total, resolvedProfile),
            activeKcal = kcalFrom(metMinutes.active, resolvedProfile),
            metMinutes = metMinutes.total,
            model = CalorieModel.COMPENDIUM_VELOCITY,
            uncertaintyMinPercent = resolvedProfile.uncertaintyMinPercent,
            uncertaintyMaxPercent = resolvedProfile.uncertaintyMaxPercent,
            hasProfile = resolvedProfile.hasProfile,
            missingInputs = missingInputs(profile),
            assumedWeightKg = resolvedProfile.assumedWeightKg,
        )
    }

    private fun emptyResult(): CalorieResult =
        CalorieResult(
            totalKcal = null,
            activeKcal = null,
            metMinutes = 0.0,
            model = CalorieModel.NONE,
            uncertaintyMinPercent = WITHOUT_PROFILE_MIN_PERCENT,
            uncertaintyMaxPercent = WITHOUT_PROFILE_MAX_PERCENT,
            hasProfile = false,
            missingInputs = listOf("no data"),
        )

    private fun accumulateMetMinutes(
        speedSamplesMps: List<Double>,
        durationsSeconds: List<Double>,
        isMoving: List<Boolean>,
    ): MetMinutes {
        var total = 0.0
        var active = 0.0
        val count = minOf(speedSamplesMps.size, durationsSeconds.size, isMoving.size)
        for (index in 0 until count) {
            val sample =
                metMinutesForSample(
                    speedMps = speedSamplesMps[index],
                    durationSeconds = durationsSeconds[index],
                    moving = isMoving[index],
                )
            total += sample.total
            active += sample.active
        }
        return MetMinutes(total = total, active = active)
    }

    private fun metMinutesForSample(
        speedMps: Double,
        durationSeconds: Double,
        moving: Boolean,
    ): MetMinutes {
        if (durationSeconds <= 0.0 || !durationSeconds.isFinite()) {
            return MetMinutes(total = 0.0, active = 0.0)
        }
        val durationMinutes = durationSeconds / SECONDS_PER_MINUTE
        val metBand = if (moving) MetTable.getMetForSpeedMps(speedMps) else null
        val met = metBand?.met ?: MetTable.STOP_MET
        val total = met * durationMinutes
        val active = if (metBand != null) total else 0.0
        return MetMinutes(total = total, active = active)
    }

    private fun resolveProfile(profile: UserProfile): ResolvedProfile {
        val hasProfile = profile.weightKg != null && profile.weightKg > 0.0
        val assumedWeightKg = if (profile.weightKg == null) DEFAULT_WEIGHT_KG else null
        return ResolvedProfile(
            weightKg = profile.weightKg ?: DEFAULT_WEIGHT_KG,
            assumedWeightKg = assumedWeightKg,
            hasProfile = hasProfile,
            uncertaintyMinPercent = if (hasProfile) WITH_PROFILE_MIN_PERCENT else WITHOUT_PROFILE_MIN_PERCENT,
            uncertaintyMaxPercent = if (hasProfile) WITH_PROFILE_MAX_PERCENT else WITHOUT_PROFILE_MAX_PERCENT,
        )
    }

    private fun kcalFrom(
        metMinutes: Double,
        profile: ResolvedProfile,
    ): Double? =
        if (profile.hasProfile || profile.assumedWeightKg != null) {
            metMinutes * OXYGEN_ML_PER_KG_PER_MIN_PER_MET * profile.weightKg / KCAL_CONVERSION_DENOMINATOR
        } else {
            null
        }

    private fun missingInputs(profile: UserProfile): List<String> =
        buildList {
            if (profile.weightKg == null) add("weight")
            if (profile.heightCm == null) add("height")
            if (profile.ageYears == null) add("age")
            if (profile.isMale == null) add("sex")
        }

    private data class ResolvedProfile(
        val weightKg: Double,
        val assumedWeightKg: Double?,
        val hasProfile: Boolean,
        val uncertaintyMinPercent: Int,
        val uncertaintyMaxPercent: Int,
    )

    private data class MetMinutes(
        val total: Double,
        val active: Double,
    )
}
