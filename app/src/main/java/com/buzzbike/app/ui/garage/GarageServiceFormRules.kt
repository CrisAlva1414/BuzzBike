package com.buzzbike.app.ui.garage

internal const val SERVICE_VALUE_INVALID_MESSAGE = "Ingresá un número mayor o igual a 0."

/** Validates a service form and parses the optional km/hours consumed. */
internal fun validateServiceForm(form: GarageForm.Service): ValidServiceForm {
    val km = parseServiceAxis(form.km)
    val hours = parseServiceAxis(form.hours)
    return ValidServiceForm(
        form =
            form.copy(
                kmError = if (isInvalidServiceAxis(form.km)) SERVICE_VALUE_INVALID_MESSAGE else null,
                hoursError = if (isInvalidServiceAxis(form.hours)) SERVICE_VALUE_INVALID_MESSAGE else null,
            ),
        consumedKm = km,
        consumedHours = hours,
    )
}

private fun parseServiceAxis(text: String): Double? = if (text.isBlank()) null else parseDecimal(text)

private fun isInvalidServiceAxis(text: String): Boolean =
    text.isNotBlank() && parseDecimal(text)?.let { !it.isFinite() || it < 0.0 } ?: true
