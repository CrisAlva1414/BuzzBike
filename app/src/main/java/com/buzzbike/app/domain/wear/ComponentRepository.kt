package com.buzzbike.app.domain.wear

import kotlinx.coroutines.flow.Flow

/**
 * Port that owns the wearable components of a bike and their wear assessment.
 *
 * **Ride attribution rule:** only rides explicitly assigned to a bike
 * (`rides.bike_id`) contribute to that bike's wear. A ride without a bike never
 * affects any component; the user decides which bike each ride belongs to.
 * Kms and hours are summed from the latest `SERVICE` event for the component
 * (its accumulation baseline) or from the install date when it was never
 * serviced. Elapsed days are measured from the same baseline.
 */
interface ComponentRepository {
    /** Emits the non-retired components of [bikeId], ordered by type then label. */
    fun observeComponents(bikeId: String): Flow<List<BikeComponent>>

    suspend fun getComponent(id: String): BikeComponent?

    /**
     * Installs [draft] on its bike and returns the generated id.
     *
     * `installedAtMillis` null means "use the injected clock"; pass an explicit
     * value when importing a component that was installed in the past.
     */
    suspend fun createComponent(draft: NewComponent): String

    /** Replaces the editable fields of an existing component. */
    suspend fun updateComponent(component: BikeComponent)

    /** Soft-retires a component; defaults to the injected clock. */
    suspend fun retireComponent(
        id: String,
        retiredAtMillis: Long? = null,
    )

    /**
     * Records a `SERVICE` event that **resets** accumulated wear: usage is
     * summed from this event's timestamp onwards.
     *
     * [consumedKm], [consumedHours] and [wearRatio] are the values the component
     * had reached before the service; they are stored for the event log and do
     * not affect the new baseline.
     */
    suspend fun logService(
        componentId: String,
        consumedKm: Double?,
        consumedHours: Double?,
        wearRatio: Double?,
        notes: String? = null,
    )

    /**
     * Assesses every active component of [bikeId].
     *
     * Returns components ordered by type then label; unknown persisted type
     * values are skipped instead of crashing (forward-compatible enum mapping).
     */
    suspend fun assessWear(bikeId: String): List<ComponentWear>
}
