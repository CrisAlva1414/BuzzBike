package com.buzzbike.app.data.export

import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.data.local.entity.TrackPointEntity
import com.buzzbike.app.data.repository.RideRepository
import com.buzzbike.app.domain.export.GpxRide
import com.buzzbike.app.domain.export.GpxWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * End-to-end JVM coverage of the export path, from Room-shaped entities to the
 * GPX document handed to the SAF exporter.
 *
 * It wires the real [RideRepository] (with in-memory DAO fakes), the real
 * [GpxWriter] and a persisted ride that carries altitude, speed and bearing, so
 * a regression in any of the three layers (mapping, ordering, serialization)
 * fails here instead of only in the unit tests of one layer.
 */
class HistoryExportIntegrationTest {
    @Test
    fun given_completedRideWithPoints_when_detailIsExported_then_xmlCarriesTheRealTrace() =
        runTest {
            val repository = RideRepository(FakeRideDao(listOf(ride)), FakeTrackPointDao(points))

            val detail = requireNotNull(repository.getRideDetail(RIDE_ID))
            val xml =
                GpxWriter.write(
                    GpxRide(
                        name = "Ride de prueba",
                        startTimeMillis = detail.ride.startTimeMillis,
                        points = detail.points,
                    ),
                )

            assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
            assertEquals(EXPECTED_TRACK_POINTS, Regex("<trkpt ").findAll(xml).count())
            assertEquals(EXPECTED_SEGMENTS, Regex("<trkseg>").findAll(xml).count())
            assertEquals(-33.45, detail.points.first().lat, 0.0)
            assertEquals(512.25, requireNotNull(detail.points.first().altitudeMslM), 0.0)

            assertTrue(xml.contains("<ele>512.250</ele>"))
            assertTrue(xml.contains("<gpxtpx:speed>5.500</gpxtpx:speed>"))
            assertTrue(xml.contains("<gpxtpx:course>45.50</gpxtpx:course>"))
            assertTrue(xml.contains("<time>${formatTime(START_MILLIS)}</time>"))
            assertTrue(xml.contains("<time>${formatTime(START_MILLIS + SECOND_POINT_OFFSET_MILLIS)}</time>"))

            val firstPoint = xml.substringAfter("<trkpt ").substringBefore("</trkpt>")
            assertTrue(firstPoint.indexOf("<ele>") < firstPoint.indexOf("<time>"))
            assertTrue(firstPoint.indexOf("<time>") < firstPoint.indexOf("<extensions>"))
        }

    private fun formatTime(millis: Long): String = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(millis))

    private class FakeRideDao(
        private val rides: List<RideEntity> = emptyList(),
    ) : RideDao {
        override fun getAllRides(): Flow<List<RideEntity>> = flowOf(rides)

        override suspend fun getRideById(id: String): RideEntity? = rides.firstOrNull { it.id == id }

        override fun getActiveRide(): Flow<RideEntity?> = flowOf(rides.firstOrNull { it.isActive })

        override suspend fun getActiveRideId(): String? = rides.firstOrNull { it.isActive }?.id

        override suspend fun insertRide(ride: RideEntity) = Unit

        override suspend fun updateRide(ride: RideEntity) = Unit

        override suspend fun deactivateAllRides() = Unit

        override suspend fun deleteRide(id: String) = Unit
    }

    private class FakeTrackPointDao(
        private val points: List<TrackPointEntity> = emptyList(),
    ) : TrackPointDao {
        override suspend fun insertPoint(point: TrackPointEntity) = Unit

        override suspend fun insertPoints(points: List<TrackPointEntity>) = Unit

        override fun getPointsForRide(rideId: String): Flow<List<TrackPointEntity>> = emptyFlow()

        override suspend fun getPointsForRideSync(rideId: String): List<TrackPointEntity> = points

        override suspend fun deletePointsForRide(rideId: String) = Unit
    }

    private companion object {
        const val RIDE_ID = "ride-export"
        const val START_MILLIS = 1_700_000_000_000L
        const val SECOND_POINT_OFFSET_MILLIS = 60_000L
        const val EXPECTED_TRACK_POINTS = 2
        const val EXPECTED_SEGMENTS = 1
        const val FIRST_POINT_NANOS = 5_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val SECOND_POINT_NANOS = FIRST_POINT_NANOS + SECOND_POINT_OFFSET_MILLIS * NANOS_PER_MILLI

        val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter
                .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
                .withZone(ZoneOffset.UTC)

        val ride =
            RideEntity(
                id = RIDE_ID,
                startTimeMillis = START_MILLIS,
                endTimeMillis = START_MILLIS + 1_800_000L,
                distanceM = 12_350.0,
                movingTimeSeconds = 2_700L,
                elevationGainM = 120.0,
                elevationLossM = 80.0,
                avgSpeedMps = 4.57,
                maxSpeedMps = 11.2,
                isActive = false,
            )

        val points =
            listOf(
                TrackPointEntity(
                    id = "p1",
                    rideId = RIDE_ID,
                    timestampNanos = FIRST_POINT_NANOS,
                    lat = -33.45,
                    lon = -70.66,
                    altitudeMslM = 512.25,
                    speedMps = 5.5,
                    bearing = 45.5f,
                ),
                TrackPointEntity(
                    id = "p2",
                    rideId = RIDE_ID,
                    timestampNanos = SECOND_POINT_NANOS,
                    lat = -33.46,
                    lon = -70.67,
                    altitudeMslM = 514.5,
                    speedMps = 6.0,
                    bearing = 46.0f,
                ),
            )
    }
}
