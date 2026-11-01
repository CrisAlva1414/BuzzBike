package com.buzzbike.app.ui.garage

/**
 * Builds the rename form for [bikeId] from the current content.
 *
 * Returns `null` when the bike is no longer in the garage (for example, another
 * device removed it or the screen was stale); the view model then keeps the
 * form closed instead of opening a form that cannot be submitted.
 */
internal fun renameForm(
    state: GarageUiState,
    bikeId: String,
): GarageForm.Bike? =
    (state as? GarageUiState.Content)
        ?.bikes
        ?.firstOrNull { it.bike.id == bikeId }
        ?.bike
        ?.let { GarageForm.Bike(bikeId = it.id, name = it.name) }

/**
 * Builds the service form for [componentId], prefilled with the accumulated
 * km and hours the repository already assessed.
 *
 * The ratio is captured at open time so the event log stores the wear the
 * component had right before the service, not a post-reset value.
 */
internal fun serviceForm(
    state: GarageUiState,
    componentId: String,
): GarageForm.Service? =
    (state as? GarageUiState.Content)
        ?.bikes
        ?.asSequence()
        ?.flatMap { it.components.asSequence() }
        ?.firstOrNull { it.component.id == componentId }
        ?.let { wear ->
            GarageForm.Service(
                componentId = wear.component.id,
                componentLabel = wear.component.label,
                km = GarageFormat.inputDecimal(wear.usage.km),
                hours = GarageFormat.inputDecimal(wear.usage.hours),
                notes = "",
                wearRatio = wear.assessment.ratio,
            )
        }
