package com.buzzbike.app.domain.calories

/**
 * User profile for calorie calculation (all optional).
 */
data class UserProfile(
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val ageYears: Int? = null,
    val isMale: Boolean? = null,
)

/**
 * Calorie calculation result with uncertainty.
 */
data class CalorieResult(
    val totalKcal: Double?,
    val activeKcal: Double?,
    val metMinutes: Double,
    val model: CalorieModel,
    val uncertaintyMinPercent: Int,
    val uncertaintyMaxPercent: Int,
    val hasProfile: Boolean,
    val missingInputs: List<String>,
    val assumedWeightKg: Double? = null,
)

/**
 * Calorie calculation model used.
 */
enum class CalorieModel {
    COMPENDIUM_VELOCITY,
    KEYTEL_HR,
    ACSM_POWER,
    NONE,
}
