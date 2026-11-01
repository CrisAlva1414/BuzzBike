package com.buzzbike.app.ui.garage

import com.buzzbike.app.domain.wear.WearLifecycle
import com.buzzbike.app.domain.wear.WearType

/** Maximum bike name length accepted by the create/rename form. */
internal const val MAX_BIKE_NAME_LENGTH = 40

/** Maximum component label length; keeps long labels readable on the card. */
internal const val MAX_COMPONENT_LABEL_LENGTH = 40

/**
 * One editable garage form.
 *
 * The form is a sealed hierarchy so a single dialog host can render the bike,
 * component or service form without nullable "which field applies" flags.
 * Numeric lifecycle and service values stay as raw text until the user submits,
 * so partial input never reaches the domain.
 */
sealed interface GarageForm {
    data class Bike(
        val bikeId: String?,
        val name: String,
        val nameError: String? = null,
    ) : GarageForm

    data class Component(
        val bikeId: String,
        val type: WearType,
        val label: String,
        val lifecycleKm: String,
        val lifecycleHours: String,
        val lifecycleDays: String,
        val labelError: String? = null,
        val lifecycleError: String? = null,
    ) : GarageForm

    data class Service(
        val componentId: String,
        val componentLabel: String,
        val km: String,
        val hours: String,
        val notes: String,
        val wearRatio: Double?,
        val kmError: String? = null,
        val hoursError: String? = null,
    ) : GarageForm
}

/** What the user asked to open; the view model resolves it into a [GarageForm]. */
sealed interface GarageFormTarget {
    data object CreateBike : GarageFormTarget

    data class RenameBike(
        val bikeId: String,
    ) : GarageFormTarget

    data class CreateComponent(
        val bikeId: String,
    ) : GarageFormTarget

    data class ServiceComponent(
        val componentId: String,
    ) : GarageFormTarget
}

/**
 * Result of a completed mutation.
 *
 * [selectedBikeId] is set when the operation produces a bike that should become
 * the active one (for example, creating the first bike). A `null` means "keep
 * the current selection".
 */
internal data class MutationOutcome(
    val message: String?,
    val selectedBikeId: String? = null,
)

/** A validated component form plus the lifecycle it will persist. */
internal data class ValidComponentForm(
    val form: GarageForm.Component,
    val lifecycle: WearLifecycle?,
)

/** A validated service form with the optional consumed values parsed. */
internal data class ValidServiceForm(
    val form: GarageForm.Service,
    val consumedKm: Double?,
    val consumedHours: Double?,
)
