package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.BikeDao
import com.buzzbike.app.data.local.dao.ComponentDao
import com.buzzbike.app.data.local.dao.ComponentEventDao
import com.buzzbike.app.data.local.dao.RideUsageDao
import com.buzzbike.app.data.local.entity.ComponentEntity
import com.buzzbike.app.data.local.entity.ComponentEventEntity
import com.buzzbike.app.domain.wear.BikeComponent
import com.buzzbike.app.domain.wear.ComponentRepository
import com.buzzbike.app.domain.wear.ComponentUsage
import com.buzzbike.app.domain.wear.ComponentWear
import com.buzzbike.app.domain.wear.NewComponent
import com.buzzbike.app.domain.wear.WearClock
import com.buzzbike.app.domain.wear.WearEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [ComponentRepository].
 *
 * Wear is computed from the database, never read from a cached snapshot: km and
 * hours are summed from the latest `SERVICE` event (or the install date) over
 * rides assigned to the bike, and days are elapsed time from the same baseline.
 * [WearEngine] stays the single source of truth for the ratio/status formula.
 */
@Singleton
class RoomComponentRepository
    @Inject
    constructor(
        private val bikeDao: BikeDao,
        private val componentDao: ComponentDao,
        private val eventDao: ComponentEventDao,
        private val rideUsageDao: RideUsageDao,
        private val clock: WearClock,
    ) : ComponentRepository {
        override fun observeComponents(bikeId: String): Flow<List<BikeComponent>> =
            componentDao.observeActiveComponents(bikeId).map { rows ->
                rows.mapNotNull(ComponentEntity::toBikeComponentOrNull)
            }

        override suspend fun getComponent(id: String): BikeComponent? =
            componentDao.getComponent(id)?.toBikeComponentOrNull()

        override suspend fun createComponent(draft: NewComponent): String {
            requireNotNull(bikeDao.getBike(draft.bikeId)) { "unknown bike: ${draft.bikeId}" }
            val id = UUID.randomUUID().toString()
            componentDao.insertComponent(
                ComponentEntity(
                    id = id,
                    bikeId = draft.bikeId,
                    type = draft.type.toStorage(),
                    label = requireNonBlank(draft.label, "component label"),
                    installedAtMillis = draft.installedAtMillis ?: clock.nowMillis(),
                    lifecycleKm = draft.lifecycle?.lifecycleKm,
                    lifecycleHours = draft.lifecycle?.lifecycleHours,
                    lifecycleDays = draft.lifecycle?.lifecycleDays,
                    notes = draft.notes,
                ),
            )
            return id
        }

        override suspend fun updateComponent(component: BikeComponent) {
            requireNotNull(componentDao.getComponent(component.id)) {
                "unknown component: ${component.id}"
            }
            requireNotNull(bikeDao.getBike(component.bikeId)) {
                "unknown bike: ${component.bikeId}"
            }
            componentDao.updateComponent(
                component.copy(label = requireNonBlank(component.label, "component label")).toComponentEntity(),
            )
        }

        override suspend fun retireComponent(
            id: String,
            retiredAtMillis: Long?,
        ) {
            requireNotNull(componentDao.getComponent(id)) { "unknown component: $id" }
            componentDao.retireComponent(id, retiredAtMillis ?: clock.nowMillis())
        }

        override suspend fun logService(
            componentId: String,
            consumedKm: Double?,
            consumedHours: Double?,
            wearRatio: Double?,
            notes: String?,
        ) {
            requireNotNull(componentDao.getComponent(componentId)) {
                "unknown component: $componentId"
            }
            requireValidMeasurement(consumedKm, "consumedKm")
            requireValidMeasurement(consumedHours, "consumedHours")
            requireValidMeasurement(wearRatio, "wearRatio")
            eventDao.insertEvent(
                ComponentEventEntity(
                    id = UUID.randomUUID().toString(),
                    componentId = componentId,
                    type = ComponentEventEntity.TYPE_SERVICE,
                    atMillis = clock.nowMillis(),
                    wearRatioAtEvent = wearRatio,
                    consumedKm = consumedKm,
                    consumedHours = consumedHours,
                    notes = notes,
                ),
            )
        }

        override suspend fun assessWear(bikeId: String): List<ComponentWear> {
            val now = clock.nowMillis()
            return componentDao.getActiveComponents(bikeId).mapNotNull { entity ->
                entity.toBikeComponentOrNull()?.let { component -> assess(component, now) }
            }
        }

        /**
         * Builds usage and assessment for one component.
         *
         * The baseline is the latest `SERVICE` event or the install date. There
         * is deliberately no fallback to "all rides": an unassigned ride must
         * never inflate a bike's wear.
         */
        private suspend fun assess(
            component: BikeComponent,
            nowMillis: Long,
        ): ComponentWear {
            val baseline =
                eventDao.getLastServiceEvent(component.id)?.atMillis ?: component.installedAtMillis
            val sum = rideUsageDao.usageForBikeSince(component.bikeId, baseline)
            val usage =
                ComponentUsage(
                    km = nonNegative(sum.distanceM) / METERS_PER_KILOMETER,
                    hours = nonNegative(sum.movingTimeSeconds.toDouble()) / SECONDS_PER_HOUR,
                    days = (nowMillis - baseline).coerceAtLeast(0L) / MILLIS_PER_DAY,
                )
            return ComponentWear(
                component = component,
                usage = usage,
                assessment = WearEngine.assess(component.lifecycle, usage),
                baselineMillis = baseline,
            )
        }

        /**
         * Rejects negative or non-finite measurements before they reach the
         * event log. [value] is null when the axis is not tracked.
         */
        private fun requireValidMeasurement(
            value: Double?,
            field: String,
        ) {
            require(value == null || value.isFinite() && value >= 0.0) {
                "$field must be a non-negative finite number"
            }
        }

        /** Clamps corrupt aggregates to zero so the usage model stays valid. */
        private fun nonNegative(value: Double): Double = if (value.isFinite() && value > 0.0) value else 0.0

        private companion object {
            const val METERS_PER_KILOMETER = 1000.0
            const val SECONDS_PER_HOUR = 3600.0
            const val MILLIS_PER_DAY = 86_400_000.0
        }
    }
