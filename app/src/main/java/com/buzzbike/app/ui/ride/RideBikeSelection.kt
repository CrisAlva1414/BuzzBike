package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.wear.Bike

/**
 * Garage snapshot used by the ride screen before a ride starts.
 *
 * Only active (non-archived) bikes are offered. [selectedBikeId] is `null` by
 * default and whenever the chosen bike disappears from the garage, which maps
 * to the "Sin bici" option. The selection lives only in the view model: once a
 * ride starts its bike is persisted in `rides.bike_id` and the live selection
 * is irrelevant (ADR-004).
 *
 * @property isBusy true while a start request is in flight; the selector must
 *   be disabled so the user cannot change the bike mid-transition.
 */
data class RideBikeSelection(
    val bikes: List<Bike>,
    val selectedBikeId: String?,
    val isBusy: Boolean,
) {
    /** Selected bike, or `null` when "Sin bici" is active. */
    val selectedBike: Bike? get() = bikes.firstOrNull { it.id == selectedBikeId }

    /** Human-readable label of the selection, used by the visible header. */
    val selectedLabel: String get() = selectedBike?.name ?: NO_BIKE_LABEL

    companion object {
        const val NO_BIKE_LABEL = "Sin bici"

        val empty: RideBikeSelection = RideBikeSelection(emptyList(), null, false)
    }
}
