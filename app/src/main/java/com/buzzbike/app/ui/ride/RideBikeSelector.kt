package com.buzzbike.app.ui.ride

import com.buzzbike.app.domain.wear.Bike
import com.buzzbike.app.domain.wear.BikeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * State holder for the bike chosen before a ride starts.
 *
 * Keeping this logic out of [RideViewModel] isolates the selector lifecycle
 * (garage flow, validation, busy flag) from the session actions, so all of it
 * can be tested without a fake session.
 *
 * The selector only offers non-archived bikes. The selected id is validated
 * against the current garage on every emission: deleting or archiving the
 * chosen bike falls back to the "Sin bici" option. A selection is captured
 * once by [beginStart] and persisted in `rides.bike_id`; later garage edits do
 * not change the ride (ADR-004).
 *
 * @param scope coroutine scope that owns the garage collection (the view
 *   model scope in production).
 */
class RideBikeSelector(
    bikeRepository: BikeRepository,
    scope: CoroutineScope,
) {
    private val activeBikes: StateFlow<List<Bike>> =
        bikeRepository
            .observeBikes()
            .map { bikes -> bikes.filterNot { it.isArchived } }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    private val selectedBikeId = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    /** Selector state consumed by the UI. */
    val state: StateFlow<RideBikeSelection> =
        combine(activeBikes, selectedBikeId, busy) { bikes, selectedId, isBusy ->
            RideBikeSelection(
                bikes = bikes,
                selectedBikeId = selectedId?.takeIf { id -> bikes.any { it.id == id } },
                isBusy = isBusy,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = RideBikeSelection.empty,
        )

    /**
     * Selects [bikeId] for the next ride, or "Sin bici" when it is null.
     * Ignores changes while a start is in flight and ids that are no longer in
     * the active garage.
     */
    fun select(bikeId: String?) {
        if (busy.value) return
        selectedBikeId.value = bikeId?.takeIf { id -> activeBikes.value.any { it.id == id } }
    }

    /**
     * Marks the start as in flight and returns the bike id to persist.
     *
     * Reading the selection here — synchronously, before the coroutine that
     * creates the ride — is what fixes it for the whole ride: a later garage
     * edit cannot change what was captured.
     */
    fun beginStart(): String? {
        busy.value = true
        return selectedBikeId.value?.takeIf { id -> activeBikes.value.any { it.id == id } }
    }

    /** Releases the busy flag once the start attempt finished. */
    fun endStart() {
        busy.value = false
    }
}
