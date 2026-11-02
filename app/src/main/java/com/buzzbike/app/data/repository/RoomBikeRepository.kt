package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.dao.BikeDao
import com.buzzbike.app.data.local.dao.RideUsageDao
import com.buzzbike.app.data.local.entity.BikeEntity
import com.buzzbike.app.domain.wear.Bike
import com.buzzbike.app.domain.wear.BikeRepository
import com.buzzbike.app.domain.wear.MAX_GARAGE_NAME_LENGTH
import com.buzzbike.app.domain.wear.WearClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [BikeRepository].
 *
 * The database is encrypted as a whole by SQLCipher, so bike and ride
 * assignments inherit encryption at rest (AGENTS.md §7).
 */
@Singleton
class RoomBikeRepository
    @Inject
    constructor(
        private val bikeDao: BikeDao,
        private val rideUsageDao: RideUsageDao,
        private val clock: WearClock,
    ) : BikeRepository {
        override fun observeBikes(): Flow<List<Bike>> =
            bikeDao.observeBikes().map { rows -> rows.map(BikeEntity::toBike) }

        override suspend fun getBike(id: String): Bike? = bikeDao.getBike(id)?.toBike()

        override suspend fun createBike(
            name: String,
            notes: String?,
        ): String {
            val id = UUID.randomUUID().toString()
            bikeDao.insertBike(
                BikeEntity(
                    id = id,
                    name = requireNonBlank(name, "bike name"),
                    notes = ComponentInputValidator.normalizeNotes(notes),
                    createdAtMillis = clock.nowMillis(),
                ),
            )
            return id
        }

        override suspend fun updateBike(bike: Bike) {
            val normalized =
                bike.copy(
                    name = requireNonBlank(bike.name, "bike name"),
                    notes = ComponentInputValidator.normalizeNotes(bike.notes),
                )
            bikeDao.updateBike(normalized.toBikeEntity())
        }

        override suspend fun deleteBike(id: String) {
            bikeDao.deleteBikeCascade(id)
        }

        override suspend fun assignRideToBike(
            rideId: String,
            bikeId: String?,
        ) {
            if (bikeId != null) {
                requireNotNull(bikeDao.getBike(bikeId)) { "unknown bike: $bikeId" }
            }
            rideUsageDao.assignRideToBike(rideId, bikeId)
        }
    }

/**
 * Trims [value] and rejects an empty or over-long result.
 *
 * Bike names and component labels come from user input; rejecting blank values
 * here keeps the garage free of unnamed rows, and [maxLength] mirrors the UI
 * bound so a caller bypassing the form cannot persist an unbounded string.
 */
internal fun requireNonBlank(
    value: String,
    field: String,
    maxLength: Int = MAX_GARAGE_NAME_LENGTH,
): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty()) { "$field must not be blank" }
    require(trimmed.length <= maxLength) { "$field must be at most $maxLength characters" }
    return trimmed
}
