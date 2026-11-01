package com.buzzbike.app.ui.garage

import com.buzzbike.app.domain.wear.ComponentDefaults
import com.buzzbike.app.domain.wear.WearLifecycle
import com.buzzbike.app.domain.wear.WearType

internal const val COMPONENT_LABEL_REQUIRED_MESSAGE = "La etiqueta del componente es obligatoria."
internal const val COMPONENT_LABEL_TOO_LONG_MESSAGE = "La etiqueta no puede superar los 40 caracteres."
internal const val LIFECYCLE_REQUIRED_MESSAGE = "Configurá al menos un ciclo de vida: km, horas o días."
internal const val LIFECYCLE_INVALID_MESSAGE = "Cada ciclo de vida debe ser un número mayor a 0."

/**
 * Builds a component form with the ADR-004 defaults for [type].
 *
 * Defaults are editable product starting points, not factory truth: the user
 * can change every field before saving. Brakes deliberately start empty because
 * ADR-004 defines no default lifecycle for them.
 */
internal fun newComponentForm(
    bikeId: String,
    type: WearType = WearType.CHAIN,
): GarageForm.Component {
    val lifecycle = ComponentDefaults.lifecycleFor(type)
    return GarageForm.Component(
        bikeId = bikeId,
        type = type,
        label = "",
        lifecycleKm = lifecycleInput(lifecycle?.lifecycleKm),
        lifecycleHours = lifecycleInput(lifecycle?.lifecycleHours),
        lifecycleDays = lifecycleInput(lifecycle?.lifecycleDays),
    )
}

/**
 * Re-prefills the lifecycle for [type] while keeping the label.
 *
 * Called when the user switches the component type: keeping an old chain's
 * 2000 km cycle on a suspension would silently configure the wrong axis.
 */
internal fun componentFormWithType(
    form: GarageForm.Component,
    type: WearType,
): GarageForm.Component {
    val defaults = newComponentForm(form.bikeId, type)
    return defaults.copy(label = form.label, labelError = null)
}

/**
 * Validates a component form and returns it with field errors plus the parsed
 * lifecycle. A form with errors keeps `lifecycle = null` and must not be sent
 * to the repository.
 */
internal fun validateComponentForm(form: GarageForm.Component): ValidComponentForm {
    val labelError =
        when {
            form.label.isBlank() -> COMPONENT_LABEL_REQUIRED_MESSAGE
            form.label.trim().length > MAX_COMPONENT_LABEL_LENGTH -> COMPONENT_LABEL_TOO_LONG_MESSAGE
            else -> null
        }
    val km = parseLifecycleAxis(form.lifecycleKm)
    val hours = parseLifecycleAxis(form.lifecycleHours)
    val days = parseLifecycleAxis(form.lifecycleDays)
    val axes = listOf(form.lifecycleKm, form.lifecycleHours, form.lifecycleDays)
    val invalid = axes.any(::isInvalidLifecycleAxis)
    val lifecycleError =
        when {
            invalid -> LIFECYCLE_INVALID_MESSAGE
            listOfNotNull(km, hours, days).isEmpty() -> LIFECYCLE_REQUIRED_MESSAGE
            else -> null
        }
    val lifecycle =
        if (lifecycleError == null) {
            WearLifecycle(lifecycleKm = km, lifecycleHours = hours, lifecycleDays = days)
        } else {
            null
        }
    return ValidComponentForm(
        form = form.copy(labelError = labelError, lifecycleError = lifecycleError),
        lifecycle = lifecycle,
    )
}

/** Formats a lifecycle default for the editable text field, without decimals. */
private fun lifecycleInput(value: Double?): String =
    value
        ?.let {
            if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
        }.orEmpty()

private fun parseLifecycleAxis(text: String): Double? = if (text.isBlank()) null else parseDecimal(text)

private fun isInvalidLifecycleAxis(text: String): Boolean =
    text.isNotBlank() && parseDecimal(text)?.let { !it.isFinite() || it <= 0.0 } ?: true
