package com.buzzbike.app.domain.calories

/**
 * Default body weight used when the user has not provided a profile.
 * ADR-001 §5.3: the result is marked as an assumption and carries the wider
 * band; the UI must label it as `"supuesto: 70 kg"`.
 */
private const val DEFAULT_WEIGHT_KG = FALLBACK_WEIGHT_KG

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
                stopMet = resolvedProfile.stopMet,
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
        stopMet: Double,
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
                    stopMet = stopMet,
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
        stopMet: Double,
    ): MetMinutes {
        if (durationSeconds <= 0.0 || !durationSeconds.isFinite()) {
            return MetMinutes(total = 0.0, active = 0.0)
        }
        val durationMinutes = durationSeconds / SECONDS_PER_MINUTE
        val metBand = if (moving) MetTable.getMetForSpeedMps(speedMps) else null
        val met = metBand?.met ?: stopMet
        val total = met * durationMinutes
        val active = if (metBand != null) total else 0.0
        return MetMinutes(total = total, active = active)
    }

    private fun resolveProfile(profile: UserProfile): ResolvedProfile {
        val hasProfile = profile.isComplete
        val weightKg = profile.weightKg ?: DEFAULT_WEIGHT_KG
        val assumedWeightKg = if (profile.weightKg == null) DEFAULT_WEIGHT_KG else null
        // ADR-001 §4: a complete profile replaces the 1.0 MET stop baseline with
        // Mifflin REE / 1440 kcal/min. Expressed as a MET-equivalent so the rest
        // of the cascade (and its kcal conversion) stays untouched.
        val stopMet =
            if (hasProfile) {
                restingKcalPerMinute(profile)
                    ?.div(kcalPerMinutePerMet(weightKg))
                    ?: MetTable.STOP_MET
            } else {
                MetTable.STOP_MET
            }
        return ResolvedProfile(
            weightKg = weightKg,
            assumedWeightKg = assumedWeightKg,
            hasProfile = hasProfile,
            stopMet = stopMet,
            uncertaintyMinPercent = if (hasProfile) WITH_PROFILE_MIN_PERCENT else WITHOUT_PROFILE_MIN_PERCENT,
            uncertaintyMaxPercent = if (hasProfile) WITH_PROFILE_MAX_PERCENT else WITHOUT_PROFILE_MAX_PERCENT,
        )
    }

    private fun kcalPerMinutePerMet(weightKg: Double): Double =
        OXYGEN_ML_PER_KG_PER_MIN_PER_MET * weightKg / KCAL_CONVERSION_DENOMINATOR

    private fun kcalFrom(
        metMinutes: Double,
        profile: ResolvedProfile,
    ): Double? =
        // Absolute kcal needs a usable mass. A `null` weight falls back to the
        // documented 70 kg assumption, while a non-positive supplied weight does
        // not produce a value (ADR-001 §5.3).
        if (profile.weightKg > 0.0) {
            metMinutes * OXYGEN_ML_PER_KG_PER_MIN_PER_MET * profile.weightKg / KCAL_CONVERSION_DENOMINATOR
        } else {
            null
        }

    private fun missingInputs(profile: UserProfile): List<String> =
        buildList {
            val weightKg = profile.weightKg
            val heightCm = profile.heightCm
            val ageYears = profile.ageYears
            if (weightKg == null || weightKg <= 0.0) add("weight")
            if (heightCm == null || heightCm <= 0.0) add("height")
            if (ageYears == null || ageYears <= 0) add("age")
            if (profile.isMale == null) add("sex")
        }

    private data class ResolvedProfile(
        val weightKg: Double,
        val assumedWeightKg: Double?,
        val hasProfile: Boolean,
        val stopMet: Double,
        val uncertaintyMinPercent: Int,
        val uncertaintyMaxPercent: Int,
    )

    private data class MetMinutes(
        val total: Double,
        val active: Double,
    )
}
