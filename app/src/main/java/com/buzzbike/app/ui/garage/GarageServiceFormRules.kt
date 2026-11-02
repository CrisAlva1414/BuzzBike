package com.buzzbike.app.ui.garage

import com.buzzbike.app.domain.wear.MAX_GARAGE_NOTES_LENGTH

internal const val SERVICE_VALUE_INVALID_MESSAGE = "Ingresá un número mayor o igual a 0."
internal const val SERVICE_NOTES_TOO_LONG_MESSAGE = "Máximo $MAX_GARAGE_NOTES_LENGTH caracteres"

/** `null` when [notes] fits the repository bound; the user-facing message otherwise. */
internal fun serviceNotesError(notes: String): String? =
    if (notes.trim().length > MAX_GARAGE_NOTES_LENGTH) SERVICE_NOTES_TOO_LONG_MESSAGE else null

/** Validates a service form and parses the optional km/hours consumed. */
internal fun validateServiceForm(form: GarageForm.Service): ValidServiceForm {
    val km = parseServiceAxis(form.km)
    val hours = parseServiceAxis(form.hours)
    return ValidServiceForm(
        form =
            form.copy(
                kmError = if (isInvalidServiceAxis(form.km)) SERVICE_VALUE_INVALID_MESSAGE else null,
                hoursError = if (isInvalidServiceAxis(form.hours)) SERVICE_VALUE_INVALID_MESSAGE else null,
                notesError = serviceNotesError(form.notes),
            ),
        consumedKm = km,
        consumedHours = hours,
    )
}

private fun parseServiceAxis(text: String): Double? = if (text.isBlank()) null else parseDecimal(text)

private fun isInvalidServiceAxis(text: String): Boolean =
    text.isNotBlank() && parseDecimal(text)?.let { !it.isFinite() || it < 0.0 } ?: true
