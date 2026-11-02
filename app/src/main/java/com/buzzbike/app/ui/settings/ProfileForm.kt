package com.buzzbike.app.ui.settings

import com.buzzbike.app.domain.calories.UserProfile

/**
 * Form parsing and validation for the body profile.
 *
 * Ranges are product limits, not physiological constants: the calorie engine
 * (ADR-001) only needs plausible inputs, and rejecting typos like 7 kg or
 * 1750 cm keeps a bad profile from silently widening or narrowing the
 * uncertainty band. Values are always entered in metric units (kg, cm, years).
 */
internal const val MIN_WEIGHT_KG = UserProfile.MIN_WEIGHT_KG
internal const val MAX_WEIGHT_KG = UserProfile.MAX_WEIGHT_KG
internal const val MIN_HEIGHT_CM = UserProfile.MIN_HEIGHT_CM
internal const val MAX_HEIGHT_CM = UserProfile.MAX_HEIGHT_CM
internal const val MIN_AGE_YEARS = UserProfile.MIN_AGE_YEARS
internal const val MAX_AGE_YEARS = UserProfile.MAX_AGE_YEARS

internal const val INVALID_WEIGHT_MESSAGE = "Ingresá un peso válido en kg."
internal const val WEIGHT_RANGE_MESSAGE = "El peso debe estar entre 20 y 300 kg."
internal const val INVALID_HEIGHT_MESSAGE = "Ingresá una altura válida en cm."
internal const val HEIGHT_RANGE_MESSAGE = "La altura debe estar entre 100 y 250 cm."
internal const val INVALID_AGE_MESSAGE = "Ingresá una edad válida en años."
internal const val AGE_RANGE_MESSAGE = "La edad debe estar entre 10 y 120 años."

/** Parsed decimal that also accepts a comma as the decimal separator. */
internal fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** Returns the user-facing error for a weight field, or `null` when valid/blank. */
internal fun weightError(text: String): String? =
    when {
        text.isBlank() -> null
        else ->
            when (val value = parseDecimal(text)) {
                null -> INVALID_WEIGHT_MESSAGE
                !in MIN_WEIGHT_KG..MAX_WEIGHT_KG -> WEIGHT_RANGE_MESSAGE
                else -> null
            }
    }

/** Returns the user-facing error for a height field, or `null` when valid/blank. */
internal fun heightError(text: String): String? =
    when {
        text.isBlank() -> null
        else ->
            when (val value = parseDecimal(text)) {
                null -> INVALID_HEIGHT_MESSAGE
                !in MIN_HEIGHT_CM..MAX_HEIGHT_CM -> HEIGHT_RANGE_MESSAGE
                else -> null
            }
    }

/** Returns the user-facing error for an age field, or `null` when valid/blank. */
internal fun ageError(text: String): String? {
    val value = text.trim().takeIf(String::isNotEmpty)?.toIntOrNull() ?: return ageValueError(text)
    return if (value in MIN_AGE_YEARS..MAX_AGE_YEARS) null else AGE_RANGE_MESSAGE
}

/**
 * `null` for a blank field (valid, means "not provided") and the invalid-number
 * message for a non-numeric one. Kept separate so [ageError] stays readable.
 */
private fun ageValueError(text: String): String? = if (text.isBlank()) null else INVALID_AGE_MESSAGE
