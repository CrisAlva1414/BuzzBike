package com.buzzbike.app.domain.wear

/**
 * A bike in the user's garage (ADR-004).
 *
 * The garage is the owner of wearable components. Archiving hides a bike from
 * the active list without deleting its components or reassigning its rides.
 */
data class Bike(
    val id: String,
    val name: String,
    val notes: String?,
    val createdAtMillis: Long,
    val isArchived: Boolean,
)

/**
 * A component installed on a [Bike], with its configured lifecycle.
 *
 * [lifecycle] is `null` when no axis is configured (e.g. brakes, which have no
 * default — ADR-004:61-63); such a component is never reported as wearing out
 * and the UI must ask the user for a lifecycle first.
 */
data class BikeComponent(
    val id: String,
    val bikeId: String,
    val type: WearType,
    val label: String,
    val installedAtMillis: Long,
    val lifecycle: WearLifecycle?,
    val retiredAtMillis: Long?,
    val notes: String?,
) {
    val isRetired: Boolean
        get() = retiredAtMillis != null
}

/**
 * One component together with its accumulated usage and wear assessment.
 *
 * @property usage km/hours summed from rides assigned to the bike since
 *   [baselineMillis]; days is elapsed calendar time since [baselineMillis].
 * @property assessment result of [WearEngine.assess] for [component]'s
 *   lifecycle and [usage].
 * @property baselineMillis last `SERVICE` event, or the install date when the
 *   component was never serviced.
 */
data class ComponentWear(
    val component: BikeComponent,
    val usage: ComponentUsage,
    val assessment: WearAssessment,
    val baselineMillis: Long,
)

/**
 * Input for installing a new component.
 *
 * A parameter object keeps the repository contract readable while preserving
 * the same validation: [installedAtMillis] null means "use the injected clock".
 */
data class NewComponent(
    val bikeId: String,
    val type: WearType,
    val label: String,
    val lifecycle: WearLifecycle?,
    val installedAtMillis: Long? = null,
    val notes: String? = null,
)
