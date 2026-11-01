package com.buzzbike.app.domain.calories

/**
 * User profile for calorie calculation (all optional).
 *
 * A profile is only **complete** when every input required by the Mifflin-St
 * Jeor resting-energy baseline is present and physically usable: positive
 * weight, positive height, positive age and a known sex. ADR-001 §5.1 and §7
 * make the narrower ±20–30% band conditional on this complete profile; partial
 * profiles keep the ±30–40% band.
 */
data class UserProfile(
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val ageYears: Int? = null,
    val isMale: Boolean? = null,
) {
    /**
     * `true` when weight, height, age and sex are all usable, as required by
     * Mifflin-St Jeor (ADR-001 §5.1). Used to decide whether the calculator may
     * use the REE baseline and the narrower uncertainty band.
     */
    val isComplete: Boolean
        get() {
            val weight = weightKg ?: 0.0
            val height = heightCm ?: 0.0
            val age = ageYears ?: 0
            return weight > 0.0 && height > 0.0 && age > 0 && isMale != null
        }
}

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
