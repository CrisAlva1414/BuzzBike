package com.buzzbike.app.domain.calories

/** Weight coefficient of the Mifflin-St Jeor equation (kcal/day per kg). */
private const val WEIGHT_COEFFICIENT = 10.0

/** Height coefficient of the Mifflin-St Jeor equation (kcal/day per cm). */
private const val HEIGHT_COEFFICIENT = 6.25

/** Age coefficient of the Mifflin-St Jeor equation (kcal/day per year). */
private const val AGE_COEFFICIENT = 5.0

/** Male constant term of the Mifflin-St Jeor equation (kcal/day). */
private const val MALE_SEX_OFFSET = 5.0

/** Female constant term of the Mifflin-St Jeor equation (kcal/day). */
private const val FEMALE_SEX_OFFSET = -161.0

/** Minutes in a day, used to turn a daily REE into kcal/min. */
private const val MINUTES_PER_DAY = 1440.0

/**
 * Mifflin-St Jeor resting energy expenditure (REE), as adopted by ADR-001 §5.1.
 *
 * # Formula
 * ```
 * REE_hombre = 10 * kg + 6.25 * cm - 5 * edad + 5    [kcal/day]
 * REE_mujer  = 10 * kg + 6.25 * cm - 5 * edad - 161  [kcal/day]
 * ```
 *
 * Units: `weightKg` in kilograms, `heightCm` in centimetres and `ageYears` in
 * years. The result is **kilocalories per day**.
 *
 * Source: Mifflin, M. D., St Jeor, S. T., et al. (1990), "A new predictive
 * equation for resting energy expenditure in healthy individuals",
 * *American Journal of Clinical Nutrition* 51(2), 241–247,
 * DOI [10.1093/ajcn/51.2.241](https://doi.org/10.1093/ajcn/51.2.241).
 *
 * Returns `null` when the profile is not [UserProfile.isComplete] (ADR-001
 * §5.1/§5.3): the equation requires weight, height, age and sex, and this
 * domain never imputes the missing ones.
 */
fun restingEnergyExpenditureKcalPerDay(profile: UserProfile): Double? {
    if (!profile.isComplete) return null
    val weightKg = profile.weightKg ?: 0.0
    val heightCm = profile.heightCm ?: 0.0
    val ageYears = profile.ageYears ?: 0
    val isMale = profile.isMale == true
    val sexOffset = if (isMale) MALE_SEX_OFFSET else FEMALE_SEX_OFFSET
    return WEIGHT_COEFFICIENT * weightKg +
        HEIGHT_COEFFICIENT * heightCm -
        AGE_COEFFICIENT * ageYears +
        sexOffset
}

/**
 * Resting energy expenditure expressed as **kcal per minute**:
 * `REE / 1440` (ADR-001 §4). This is the baseline used in place of 1.0 MET for
 * stopped samples when the profile is complete, so it only returns a value for
 * [UserProfile.isComplete] profiles.
 *
 * Source: Mifflin et al. 1990, DOI
 * [10.1093/ajcn/51.2.241](https://doi.org/10.1093/ajcn/51.2.241).
 */
fun restingKcalPerMinute(profile: UserProfile): Double? =
    restingEnergyExpenditureKcalPerDay(profile)?.div(MINUTES_PER_DAY)
