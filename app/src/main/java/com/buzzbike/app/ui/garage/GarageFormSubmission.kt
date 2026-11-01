package com.buzzbike.app.ui.garage

import com.buzzbike.app.domain.wear.BikeRepository
import com.buzzbike.app.domain.wear.ComponentRepository
import com.buzzbike.app.domain.wear.NewComponent
import com.buzzbike.app.domain.wear.WearLifecycle

private const val BIKE_CREATED_MESSAGE = "Bici agregada."
private const val BIKE_RENAMED_MESSAGE = "Bici renombrada."
private const val COMPONENT_CREATED_MESSAGE = "Componente agregado."
private const val SERVICE_LOGGED_MESSAGE = "Servicio registrado."

/**
 * Creates or renames a bike.
 *
 * The name is trimmed once here; validation already ran in the view model, so
 * an empty or over-long name never reaches this function from the UI.
 */
internal suspend fun submitBikeForm(
    bikeRepository: BikeRepository,
    form: GarageForm.Bike,
): MutationOutcome {
    val name = form.name.trim()
    val bikeId = form.bikeId
    return if (bikeId == null) {
        val createdId = bikeRepository.createBike(name)
        MutationOutcome(BIKE_CREATED_MESSAGE, selectedBikeId = createdId)
    } else {
        val bike = bikeRepository.getBike(bikeId) ?: error("unknown bike: $bikeId")
        bikeRepository.updateBike(bike.copy(name = name))
        MutationOutcome(BIKE_RENAMED_MESSAGE)
    }
}

/** Installs a validated component on its bike. */
internal suspend fun submitComponentForm(
    componentRepository: ComponentRepository,
    form: GarageForm.Component,
    lifecycle: WearLifecycle?,
): MutationOutcome {
    componentRepository.createComponent(
        NewComponent(
            bikeId = form.bikeId,
            type = form.type,
            label = form.label.trim(),
            lifecycle = lifecycle,
        ),
    )
    return MutationOutcome(COMPONENT_CREATED_MESSAGE)
}

/** Records a service that resets the component's wear accumulation. */
internal suspend fun submitServiceForm(
    componentRepository: ComponentRepository,
    validated: ValidServiceForm,
): MutationOutcome {
    val form = validated.form
    componentRepository.logService(
        componentId = form.componentId,
        consumedKm = validated.consumedKm,
        consumedHours = validated.consumedHours,
        wearRatio = form.wearRatio,
        notes = form.notes.trim().takeIf { it.isNotEmpty() },
    )
    return MutationOutcome(SERVICE_LOGGED_MESSAGE)
}
