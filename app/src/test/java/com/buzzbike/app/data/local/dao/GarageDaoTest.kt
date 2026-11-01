package com.buzzbike.app.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.data.local.AppDatabase
import com.buzzbike.app.data.local.entity.BikeEntity
import com.buzzbike.app.data.local.entity.ComponentEntity
import com.buzzbike.app.data.local.entity.ComponentEventEntity
import com.buzzbike.app.data.local.entity.RideEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests for the garage DAOs against a Room in-memory database.
 *
 * They pin the query semantics the repository relies on: active-component
 * ordering, the latest `SERVICE` baseline, ride sums scoped to one bike, and
 * the cascade deletion of bike hardware while rides keep their historical
 * `bike_id`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GarageDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var bikeDao: BikeDao
    private lateinit var componentDao: ComponentDao
    private lateinit var eventDao: ComponentEventDao
    private lateinit var rideDao: RideDao
    private lateinit var rideUsageDao: RideUsageDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        bikeDao = database.bikeDao()
        componentDao = database.componentDao()
        eventDao = database.componentEventDao()
        rideDao = database.rideDao()
        rideUsageDao = database.rideUsageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun given_retiredAndActiveComponents_when_getActive_then_orderedByTypeAndLabelWithoutRetired() =
        runTest {
            bikeDao.insertBike(bike(id = BIKE_ID))
            componentDao.insertComponent(component(id = "tires-rear", type = "TIRES", label = "Rear"))
            componentDao.insertComponent(component(id = "chain-b", type = "CHAIN", label = "B chain"))
            componentDao.insertComponent(component(id = "chain-a", type = "CHAIN", label = "A chain"))
            componentDao.insertComponent(
                component(id = "chain-retired", type = "CHAIN", label = "C chain", retired = true),
            )

            val active = componentDao.getActiveComponents(BIKE_ID)

            assertEquals(listOf("chain-a", "chain-b", "tires-rear"), active.map { it.id })
        }

    @Test
    fun given_serviceAndAlertEvents_when_getLastService_then_onlyLatestServiceIsReturned() =
        runTest {
            bikeDao.insertBike(bike(id = BIKE_ID))
            componentDao.insertComponent(component(id = COMPONENT_ID))
            eventDao.insertEvent(event(id = "service-old", type = "SERVICE", atMillis = 1_000L))
            eventDao.insertEvent(event(id = "service-new", type = "SERVICE", atMillis = 3_000L))
            eventDao.insertEvent(event(id = "approaching", type = "APPROACHING", atMillis = 4_000L))

            val latest = eventDao.getLastServiceEvent(COMPONENT_ID)

            assertEquals("service-new", latest?.id)
            assertEquals(3_000L, latest?.atMillis)
        }

    @Test
    fun given_ridesAcrossBikes_when_usageForBikeSince_then_onlyAssignedRidesAtOrAfterBaselineCount() =
        runTest {
            bikeDao.insertBike(bike(id = BIKE_ID))
            bikeDao.insertBike(bike(id = OTHER_BIKE_ID))
            rideDao.insertRide(ride(id = "before", bikeId = BIKE_ID, start = 500L, meters = 50.0, seconds = 10L))
            rideDao.insertRide(
                ride(id = "at-baseline", bikeId = BIKE_ID, start = 1_000L, meters = 100.0, seconds = 60L),
            )
            rideDao.insertRide(ride(id = "after", bikeId = BIKE_ID, start = 2_000L, meters = 200.0, seconds = 120L))
            rideDao.insertRide(
                ride(id = "other-bike", bikeId = OTHER_BIKE_ID, start = 3_000L, meters = 999.0, seconds = 999L),
            )
            rideDao.insertRide(ride(id = "unassigned", bikeId = null, start = 4_000L, meters = 777.0, seconds = 777L))

            val sum = rideUsageDao.usageForBikeSince(BIKE_ID, sinceMillis = 1_000L)

            assertEquals(300.0, sum.distanceM, 0.0)
            assertEquals(180L, sum.movingTimeSeconds)
        }

    @Test
    fun given_noMatchingRides_when_usageForBikeSince_then_zeroesInsteadOfNull() =
        runTest {
            bikeDao.insertBike(bike(id = BIKE_ID))

            val sum = rideUsageDao.usageForBikeSince(BIKE_ID, sinceMillis = 0L)

            assertEquals(0.0, sum.distanceM, 0.0)
            assertEquals(0L, sum.movingTimeSeconds)
        }

    @Test
    fun given_bikeWithRideAndComponent_when_deleted_then_rideKeepsHistoricalAssignmentAndGarageCascades() =
        runTest {
            bikeDao.insertBike(bike(id = BIKE_ID))
            componentDao.insertComponent(component(id = COMPONENT_ID))
            eventDao.insertEvent(event(id = EVENT_ID, type = "SERVICE", atMillis = 1_000L))
            rideDao.insertRide(ride(id = "assigned", bikeId = BIKE_ID, start = 2_000L))

            bikeDao.deleteBikeCascade(BIKE_ID)

            val ride = rideDao.getRideById("assigned")
            assertTrue("the historical ride must survive a bike delete", ride != null)
            assertEquals("the ride keeps the historical bike reference", BIKE_ID, ride?.bikeId)
            assertEquals(0L, countRows("bikes"))
            assertEquals(0L, countRows("components"))
            assertEquals(0L, countRows("component_events"))
        }

    private fun bike(
        id: String,
        name: String = "Bike $id",
    ) = BikeEntity(id = id, name = name, createdAtMillis = 1_000L)

    private fun component(
        id: String,
        type: String = "CHAIN",
        label: String = "Chain",
        retired: Boolean = false,
    ) = ComponentEntity(
        id = id,
        bikeId = BIKE_ID,
        type = type,
        label = label,
        installedAtMillis = 1_000L,
        retiredAtMillis = retireAt(retired),
    )

    private fun retireAt(retired: Boolean): Long? = if (retired) 2_000L else null

    private fun event(
        id: String,
        type: String,
        atMillis: Long,
    ) = ComponentEventEntity(
        id = id,
        componentId = COMPONENT_ID,
        type = type,
        atMillis = atMillis,
    )

    private fun ride(
        id: String,
        bikeId: String?,
        start: Long,
        meters: Double = 0.0,
        seconds: Long = 0L,
    ) = RideEntity(
        id = id,
        startTimeMillis = start,
        distanceM = meters,
        movingTimeSeconds = seconds,
        bikeId = bikeId,
    )

    private fun countRows(table: String): Long =
        database
            .query("SELECT COUNT(*) FROM $table", null)
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }

    private companion object {
        const val BIKE_ID = "bike-1"
        const val OTHER_BIKE_ID = "bike-2"
        const val COMPONENT_ID = "component-1"
        const val EVENT_ID = "event-1"
    }
}
