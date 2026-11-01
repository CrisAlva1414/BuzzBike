package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.entity.BikeEntity
import com.buzzbike.app.data.local.entity.ComponentEntity
import com.buzzbike.app.domain.wear.Bike
import com.buzzbike.app.domain.wear.BikeComponent
import com.buzzbike.app.domain.wear.WearLifecycle
import com.buzzbike.app.domain.wear.WearType

/** Maps a persisted bike into its domain model. */
internal fun BikeEntity.toBike(): Bike =
    Bike(
        id = id,
        name = name,
        notes = notes,
        createdAtMillis = createdAtMillis,
        isArchived = isArchived,
    )

/** Maps a domain bike into its persisted row. */
internal fun Bike.toBikeEntity(): BikeEntity =
    BikeEntity(
        id = id,
        name = name,
        notes = notes,
        createdAtMillis = createdAtMillis,
        isArchived = isArchived,
    )

/**
 * Maps a persisted component into its domain model, or `null` when the stored
 * type is unknown.
 *
 * The type is stored by name, so a database written by a newer version (or a
 * corrupted row) must not crash an older build: unknown components are simply
 * ignored by the garage queries. An invalid lifecycle row is also projected as
 * "no lifecycle" rather than thrown, matching the domain's optional contract.
 */
internal fun ComponentEntity.toBikeComponentOrNull(): BikeComponent? {
    val wearType = wearTypeFromStorage(type) ?: return null
    return BikeComponent(
        id = id,
        bikeId = bikeId,
        type = wearType,
        label = label,
        installedAtMillis = installedAtMillis,
        lifecycle = lifecycleOrNull(lifecycleKm, lifecycleHours, lifecycleDays),
        retiredAtMillis = retiredAtMillis,
        notes = notes,
    )
}

/** Maps a domain component into its persisted row. */
internal fun BikeComponent.toComponentEntity(): ComponentEntity =
    ComponentEntity(
        id = id,
        bikeId = bikeId,
        type = type.toStorage(),
        label = label,
        installedAtMillis = installedAtMillis,
        lifecycleKm = lifecycle?.lifecycleKm,
        lifecycleHours = lifecycle?.lifecycleHours,
        lifecycleDays = lifecycle?.lifecycleDays,
        retiredAtMillis = retiredAtMillis,
        notes = notes,
    )

/** Storage representation of a wear type: the enum name, never the ordinal. */
internal fun WearType.toStorage(): String = name

/**
 * Parses a persisted wear type by name.
 *
 * Returns `null` for unknown values instead of throwing or falling back to an
 * arbitrary type: an unknown component must be ignored, never misreported.
 */
internal fun wearTypeFromStorage(raw: String): WearType? = WearType.entries.firstOrNull { it.name == raw }

/**
 * Rebuilds a [WearLifecycle] from its nullable columns.
 *
 * `null` when no axis was configured. A declared axis that is NaN or not
 * positive makes the whole row unprojectable as a lifecycle (returns `null`)
 * instead of letting the domain constructor throw; infinite is accepted because
 * [WearLifecycle] documents it as an inexhaustible cycle.
 */
private fun lifecycleOrNull(
    km: Double?,
    hours: Double?,
    days: Double?,
): WearLifecycle? {
    val declared = listOfNotNull(km, hours, days)
    if (declared.isEmpty() || declared.any { it.isNaN() || it <= 0.0 }) {
        return null
    }
    return WearLifecycle(lifecycleKm = km, lifecycleHours = hours, lifecycleDays = days)
}
