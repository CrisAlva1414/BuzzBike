package com.buzzbike.app.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [RidePayloadJson].
 *
 * The encoder is output-only, so these tests check the compact UTF-8 wire
 * format, string escaping and the rule that non-finite doubles become `null`.
 */
class RidePayloadJsonTest {
    private fun payload(
        rideId: String = RIDE_ID,
        waypoints: List<Waypoint> = listOf(WAYPOINT),
    ): RideSyncPayload =
        RideSyncPayload(
            rideId = rideId,
            startTimeMillis = START_MILLIS,
            endTimeMillis = START_MILLIS + END_OFFSET_MILLIS,
            distanceM = 1_234.5,
            movingTimeSeconds = 600L,
            stoppedTimeSeconds = 60L,
            elevationGainM = 12.5,
            elevationLossM = 4.25,
            calories =
                CaloriesPayload(
                    estimateKcal = 320.0,
                    lowKcal = 224.0,
                    highKcal = 416.0,
                    model = "COMPENDIUM_VELOCITY",
                ),
            avgSpeedMps = 6.5,
            maxSpeedMps = 9.5,
            waypoints = waypoints,
        )

    private fun encodeToString(payload: RideSyncPayload): String =
        String(RidePayloadJson.encode(payload), Charsets.UTF_8)

    @Test
    fun given_fullPayload_when_encoded_then_containsRideIdMetricsAndWaypoints() {
        val json = encodeToString(payload())

        val fragments =
            listOf(
                "\"version\":2",
                "\"rideId\":\"$RIDE_ID\"",
                "\"startTimeMillis\":$START_MILLIS",
                "\"endTimeMillis\":${START_MILLIS + END_OFFSET_MILLIS}",
                "\"distanceM\":1234.5",
                "\"movingTimeSeconds\":600",
                "\"stoppedTimeSeconds\":60",
                "\"elevationGainM\":12.5",
                "\"elevationLossM\":4.25",
                "\"calories\":{\"estimateKcal\":320.0,\"lowKcal\":224.0," +
                    "\"highKcal\":416.0,\"model\":\"COMPENDIUM_VELOCITY\"}",
                "\"avgSpeedMps\":6.5",
                "\"maxSpeedMps\":9.5",
                "\"waypoints\":[{\"timestampMillis\":$START_MILLIS," +
                    "\"lat\":-33.4489,\"lon\":-70.6693,\"elevationM\":540.0}]",
            )

        fragments.forEach { fragment ->
            assertTrue("missing fragment <$fragment> in <$json>", json.contains(fragment))
        }
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun given_defaultPayload_when_encoded_thenContractVersionIsTwo() {
        val json = encodeToString(payload())

        assertTrue(json.contains("\"version\":2"))
        assertFalse(json.contains("\"version\":1"))
        assertFalse(json.contains("caloriesKcal"))
    }

    @Test
    fun given_quotesBackslashesAndControlChars_when_encoded_then_theyAreEscaped() {
        val json = encodeToString(payload(rideId = "ride\"x\\y\n\u0001"))

        assertTrue(json.contains("\"rideId\":\"ride\\\"x\\\\y\\n\\u0001\""))
        assertFalse(json.contains('\n'))
        assertFalse(json.contains('\u0001'))
    }

    @Test
    fun given_nonFiniteMetrics_when_encoded_then_theyBecomeNull() {
        val json =
            encodeToString(
                payload().copy(
                    distanceM = Double.NaN,
                    avgSpeedMps = Double.POSITIVE_INFINITY,
                    maxSpeedMps = Double.NEGATIVE_INFINITY,
                ),
            )

        assertTrue(json.contains("\"distanceM\":null"))
        assertTrue(json.contains("\"avgSpeedMps\":null"))
        assertTrue(json.contains("\"maxSpeedMps\":null"))
        assertFalse(json.contains("NaN"))
        assertFalse(json.contains("Infinity"))
    }

    @Test
    fun given_calorieBand_when_encoded_thenNestedObjectHasEstimateLowHighAndModel() {
        val json = encodeToString(payload())

        assertTrue(
            json.contains(
                "\"calories\":{\"estimateKcal\":320.0,\"lowKcal\":224.0," +
                    "\"highKcal\":416.0,\"model\":\"COMPENDIUM_VELOCITY\"}",
            ),
        )
    }

    @Test
    fun given_noCalories_when_encoded_thenCaloriesIsNull() {
        val json = encodeToString(payload().copy(calories = null))

        assertTrue(json.contains("\"calories\":null"))
    }

    @Test
    fun given_nonFiniteCalories_when_encoded_thenTheyBecomeNull() {
        val json =
            encodeToString(
                payload().copy(
                    calories =
                        CaloriesPayload(
                            estimateKcal = Double.NaN,
                            lowKcal = Double.POSITIVE_INFINITY,
                            highKcal = Double.NEGATIVE_INFINITY,
                            model = "M",
                        ),
                ),
            )

        assertTrue(
            json.contains(
                "\"calories\":{\"estimateKcal\":null,\"lowKcal\":null," +
                    "\"highKcal\":null,\"model\":\"M\"}",
            ),
        )
        assertFalse(json.contains("NaN"))
        assertFalse(json.contains("Infinity"))
    }

    @Test
    fun given_noWaypoints_when_encoded_then_waypointsIsEmptyArray() {
        val json = encodeToString(payload(waypoints = emptyList()))

        assertTrue(json.contains("\"waypoints\":[]"))
    }

    @Test
    fun given_accentedText_when_encoded_then_valueIsValidUtf8() {
        val rideId = "viaje-año"
        val bytes = RidePayloadJson.encode(payload(rideId = rideId))
        val decoded = String(bytes, Charsets.UTF_8)

        assertTrue(decoded.contains(rideId))
        assertEquals(bytes.size, decoded.toByteArray(Charsets.UTF_8).size)
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
        private const val START_MILLIS = 1_700_000_000_000L
        private const val END_OFFSET_MILLIS = 600_000L
        private val WAYPOINT =
            Waypoint(
                timestampMillis = START_MILLIS,
                lat = -33.4489,
                lon = -70.6693,
                elevationM = 540.0,
            )
    }
}
