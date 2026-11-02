package com.buzzbike.app.domain.calories

/**
 * User profile for calorie calculation (all optional).
 *
 * A profile is only **complete** when every input required by the Mifflin-St
 * Jeor resting-energy baseline is present and inside the product bounds shared
 * with the settings form: weight 20–300 kg, height 100–250 cm, age 10–120 years
 * and a known sex. ADR-001 §5.1 and §7 make the narrower ±20–30% band
 * conditional on this complete profile; partial or out-of-range profiles keep
 * the ±30–40% band.
 */
data class UserProfile(
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val ageYears: Int? = null,
    val isMale: Boolean? = null,
) {
    /**
     * `true` when weight, height, age and sex are all usable, as required by
     * Mifflin-St Jeor (ADR-001 §5.1). The product bounds are duplicated here
     * as defence in depth: a profile persisted or injected outside the form
     * must not reach the calorie engine and silently narrow the band.
     */
    val isComplete: Boolean
        get() {
            val weight = weightKg
            val height = heightCm
            val age = ageYears
            return weight != null &&
                weight in MIN_WEIGHT_KG..MAX_WEIGHT_KG &&
                height != null &&
                height in MIN_HEIGHT_CM..MAX_HEIGHT_CM &&
                age != null &&
                age in MIN_AGE_YEARS..MAX_AGE_YEARS &&
                isMale != null
        }

    companion object {
        /** Product bounds shared with the settings form (not physiological constants). */
        const val MIN_WEIGHT_KG: Double = 20.0
        const val MAX_WEIGHT_KG: Double = 300.0
        const val MIN_HEIGHT_CM: Double = 100.0
        const val MAX_HEIGHT_CM: Double = 250.0
        const val MIN_AGE_YEARS: Int = 10
        const val MAX_AGE_YEARS: Int = 120
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
