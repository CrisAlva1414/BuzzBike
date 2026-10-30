package com.buzzbike.app.data.repository

import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.domain.calories.CalorieSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-pure tests for [toRideRecord].
 *
 * The calorie band is only projected when it is complete, finite and ordered;
 * otherwise it stays `null` (ADR-001: no invented estimates).
 */
class RideRecordMapperTest {
    @Test
    fun given_completeRow_when_mapped_then_recordCarriesCalorieBandAndFields() {
        val entity =
            entity().copy(
                id = "ride-1",
                startTimeMillis = 1_700_000_000_000L,
                endTimeMillis = 1_700_000_600_000L,
                distanceM = 12_345.0,
                movingTimeSeconds = 3_600L,
                stoppedTimeSeconds = 120L,
                elevationGainM = 210.0,
                elevationLossM = 190.0,
                avgSpeedMps = 6.5,
                maxSpeedMps = 11.2,
            )

        val record = entity.toRideRecord()

        assertEquals("ride-1", record.id)
        assertEquals(1_700_000_000_000L, record.startTimeMillis)
        assertEquals(1_700_000_600_000L, record.endTimeMillis)
        assertEquals(12_345.0, record.distanceM, 0.0)
        assertEquals(3_600L, record.movingTimeSeconds)
        assertEquals(120L, record.stoppedTimeSeconds)
        assertEquals(210.0, record.elevationGainM, 0.0)
        assertEquals(190.0, record.elevationLossM, 0.0)
        assertEquals(6.5, record.avgSpeedMps, 0.0)
        assertEquals(11.2, record.maxSpeedMps, 0.0)
        assertEquals(
            CalorieSummary(estimateKcal = 100.0, lowKcal = 70.0, highKcal = 130.0, model = "MET_V3"),
            record.calories,
        )
    }

    @Test
    fun given_noCalories_when_mapped_then_caloriesNull() {
        val entity =
            entity().copy(
                caloriesKcal = null,
                caloriesLowKcal = null,
                caloriesHighKcal = null,
                calorieModel = null,
            )

        assertNull(entity.toRideRecord().calories)
    }

    @Test
    fun given_lowAboveHigh_when_mapped_then_caloriesNull() {
        val entity = entity().copy(caloriesLowKcal = 150.0, caloriesHighKcal = 130.0)

        assertNull(entity.toRideRecord().calories)
    }

    @Test
    fun given_lowEqualsHigh_when_mapped_thenBandIsKept() {
        val entity = entity().copy(caloriesLowKcal = 100.0, caloriesHighKcal = 100.0)

        assertEquals(
            CalorieSummary(estimateKcal = 100.0, lowKcal = 100.0, highKcal = 100.0, model = "MET_V3"),
            entity.toRideRecord().calories,
        )
    }

    @Test
    fun given_nonFiniteBand_when_mapped_thenCaloriesNull() {
        val entity = entity().copy(caloriesHighKcal = Double.POSITIVE_INFINITY)

        assertNull(entity.toRideRecord().calories)
    }

    @Test
    fun given_nullModel_when_mapped_then_caloriesNull() {
        val entity = entity().copy(calorieModel = null)

        assertNull(entity.toRideRecord().calories)
    }

    private fun entity(): RideEntity =
        RideEntity(
            id = "ride-1",
            startTimeMillis = 1_700_000_000_000L,
            endTimeMillis = 1_700_000_600_000L,
            distanceM = 1_000.0,
            movingTimeSeconds = 600L,
            stoppedTimeSeconds = 0L,
            elevationGainM = 10.0,
            elevationLossM = 8.0,
            caloriesKcal = 100.0,
            caloriesLowKcal = 70.0,
            caloriesHighKcal = 130.0,
            calorieModel = "MET_V3",
            avgSpeedMps = 5.0,
            maxSpeedMps = 9.0,
            isActive = false,
        )
}
