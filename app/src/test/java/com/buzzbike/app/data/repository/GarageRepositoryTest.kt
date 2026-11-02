package com.buzzbike.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.data.local.AppDatabase
import com.buzzbike.app.data.local.dao.BikeDao
import com.buzzbike.app.data.local.dao.ComponentDao
import com.buzzbike.app.data.local.dao.ComponentEventDao
import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.RideUsageDao
import com.buzzbike.app.data.local.entity.ComponentEntity
import com.buzzbike.app.data.local.entity.ComponentEventEntity
import com.buzzbike.app.data.local.entity.RideEntity
import com.buzzbike.app.domain.wear.BikeRepository
import com.buzzbike.app.domain.wear.ComponentRepository
import com.buzzbike.app.domain.wear.MAX_GARAGE_COMPONENT_LABEL_LENGTH
import com.buzzbike.app.domain.wear.MAX_GARAGE_NAME_LENGTH
import com.buzzbike.app.domain.wear.MAX_GARAGE_NOTES_LENGTH
import com.buzzbike.app.domain.wear.NewComponent
import com.buzzbike.app.domain.wear.WearAxis
import com.buzzbike.app.domain.wear.WearClock
import com.buzzbike.app.domain.wear.WearLifecycle
import com.buzzbike.app.domain.wear.WearStatus
import com.buzzbike.app.domain.wear.WearType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests for the Room-backed garage repositories.
 *
 * A mutable [WearClock] drives "now" so elapsed days and service baselines are
 * deterministic. The tests run against a Room in-memory database to exercise
 * the real DAOs, mappers and [com.buzzbike.app.domain.wear.WearEngine]
 * integration rather than a hand-written fake of the query layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GarageRepositoryTest {
    private var nowMillis = INSTALL_MILLIS
    private lateinit var database: AppDatabase
    private lateinit var bikeDao: BikeDao
    private lateinit var componentDao: ComponentDao
    private lateinit var eventDao: ComponentEventDao
    private lateinit var rideDao: RideDao
    private lateinit var rideUsageDao: RideUsageDao
    private lateinit var bikeRepository: BikeRepository
    private lateinit var componentRepository: ComponentRepository

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
        val clock = WearClock { nowMillis }
        bikeRepository = RoomBikeRepository(bikeDao, rideUsageDao, clock)
        componentRepository = RoomComponentRepository(bikeDao, componentDao, eventDao, rideUsageDao, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun given_componentWithoutRides_when_assessed_thenKmAndHoursAreZero() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            componentRepository.createComponent(
                NewComponent(
                    bikeId = bikeId,
                    type = WearType.CHAIN,
                    label = "Chain",
                    lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                    installedAtMillis = INSTALL_MILLIS,
                ),
            )
            nowMillis = INSTALL_MILLIS + DAYS * MILLIS_PER_DAY

            val wear = componentRepository.assessWear(bikeId).single()

            assertEquals(0.0, wear.usage.km, 0.0)
            assertEquals(0.0, wear.usage.hours, 0.0)
            assertEquals(DAYS.toDouble(), wear.usage.days, 0.0)
            assertEquals(WearStatus.OK, wear.assessment.status)
            assertEquals(INSTALL_MILLIS, wear.baselineMillis)
        }

    @Test
    fun given_ridesBeforeServiceOnOtherBikesAndUnassigned_when_assessed_thenOnlyAssignedRidesAfterBaselineCount() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            val otherBikeId = bikeRepository.createBike("Road")
            val componentId =
                componentRepository.createComponent(
                    NewComponent(
                        bikeId = bikeId,
                        type = WearType.SUSPENSION,
                        label = "Fork",
                        lifecycle = WearLifecycle(lifecycleHours = FORK_HOURS),
                        installedAtMillis = INSTALL_MILLIS,
                    ),
                )
            rideDao.insertRide(
                ride(id = "before-service", bikeId = bikeId, start = 1_000L, meters = 100.0, seconds = 60L),
            )
            rideDao.insertRide(
                ride(id = "after-service", bikeId = bikeId, start = SERVICE_MILLIS + 1, meters = 200.0, seconds = 120L),
            )
            rideDao.insertRide(
                ride(
                    id = "other-bike",
                    bikeId = otherBikeId,
                    start = SERVICE_MILLIS + 2,
                    meters = 999.0,
                    seconds = 999L,
                ),
            )
            rideDao.insertRide(
                ride(id = "unassigned", bikeId = null, start = SERVICE_MILLIS + 3, meters = 777.0, seconds = 777L),
            )

            nowMillis = SERVICE_MILLIS
            componentRepository.logService(componentId, consumedKm = 0.1, consumedHours = 1.0, wearRatio = 0.05)
            nowMillis = SERVICE_MILLIS + 2 * MILLIS_PER_DAY

            val wear = componentRepository.assessWear(bikeId).single()

            assertEquals(SERVICE_MILLIS, wear.baselineMillis)
            assertEquals(0.2, wear.usage.km, 1e-9)
            assertEquals(120.0 / SECONDS_PER_HOUR, wear.usage.hours, 1e-9)
            assertEquals(2.0, wear.usage.days, 0.0)
        }

    @Test
    fun given_serviceLogged_when_assessed_thenAccumulationResetsAndEventIsRecorded() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            val componentId =
                componentRepository.createComponent(
                    NewComponent(
                        bikeId = bikeId,
                        type = WearType.CHAIN,
                        label = "Chain",
                        lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                        installedAtMillis = INSTALL_MILLIS,
                    ),
                )
            rideDao.insertRide(
                ride(id = "ride", bikeId = bikeId, start = INSTALL_MILLIS + 1, meters = 1_000.0, seconds = 60L),
            )

            val before = componentRepository.assessWear(bikeId).single()
            assertEquals(1.0, before.usage.km, 1e-9)

            nowMillis = SERVICE_MILLIS
            componentRepository.logService(
                componentId = componentId,
                consumedKm = 1.0,
                consumedHours = 60.0 / SECONDS_PER_HOUR,
                wearRatio = 0.5,
                notes = "new chain",
            )
            nowMillis = SERVICE_MILLIS + 1

            val after = componentRepository.assessWear(bikeId).single()

            assertEquals(SERVICE_MILLIS, after.baselineMillis)
            assertEquals(0.0, after.usage.km, 0.0)
            assertEquals(0.0, after.usage.hours, 0.0)
            val event = requireNotNull(eventDao.getLastServiceEvent(componentId))
            assertEquals(ComponentEventEntity.TYPE_SERVICE, event.type)
            assertEquals(SERVICE_MILLIS, event.atMillis)
            assertEquals(1.0, requireNotNull(event.consumedKm), 0.0)
            assertEquals("new chain", event.notes)
        }

    @Test
    fun given_retiredComponent_when_querying_thenItIsNotListedNorAssessed() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            val componentId =
                componentRepository.createComponent(
                    NewComponent(
                        bikeId = bikeId,
                        type = WearType.TIRES,
                        label = "Rear tire",
                        lifecycle = WearLifecycle(lifecycleKm = TIRES_KM),
                    ),
                )

            nowMillis = RETIRE_MILLIS
            componentRepository.retireComponent(componentId)

            assertTrue(componentRepository.observeComponents(bikeId).first().isEmpty())
            assertTrue(componentRepository.assessWear(bikeId).isEmpty())
            assertTrue(requireNotNull(componentRepository.getComponent(componentId)).isRetired)
        }

    @Test
    fun given_unknownStoredType_when_querying_thenComponentIsSkippedWithoutCrashing() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            componentDao.insertComponent(
                ComponentEntity(
                    id = "future-component",
                    bikeId = bikeId,
                    type = "ROCKET",
                    label = "Future wear",
                    installedAtMillis = INSTALL_MILLIS,
                ),
            )
            componentRepository.createComponent(
                NewComponent(
                    bikeId = bikeId,
                    type = WearType.CHAIN,
                    label = "Chain",
                    lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                ),
            )

            val active = componentRepository.observeComponents(bikeId).first()

            assertEquals(listOf(WearType.CHAIN), active.map { it.type })
            assertEquals(listOf("Chain"), componentRepository.assessWear(bikeId).map { it.component.label })
            assertNull(componentRepository.getComponent("future-component"))
        }

    @Test
    fun given_chainAtEightyPercent_when_assessed_thenAssessmentComesFromWearEngine() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            componentRepository.createComponent(
                NewComponent(
                    bikeId = bikeId,
                    type = WearType.CHAIN,
                    label = "Chain",
                    lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                    installedAtMillis = INSTALL_MILLIS,
                ),
            )
            rideDao.insertRide(
                ride(id = "ride", bikeId = bikeId, start = INSTALL_MILLIS + 1, meters = CHAIN_EIGHTY_PERCENT_METERS),
            )
            nowMillis = INSTALL_MILLIS

            val wear = componentRepository.assessWear(bikeId).single()

            assertEquals(0.8, requireNotNull(wear.assessment.ratio), 1e-9)
            assertEquals(WearStatus.APPROACHING, wear.assessment.status)
            assertEquals(WearAxis.KM, wear.assessment.limitingAxis)
        }

    @Test
    fun given_unknownBike_when_assigningRide_thenItIsRejected() =
        runTest {
            rideDao.insertRide(ride(id = "ride", bikeId = null, start = INSTALL_MILLIS))

            val error =
                runCatching {
                    bikeRepository.assignRideToBike("ride", "missing-bike")
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertNull(rideDao.getRideById("ride")?.bikeId)
        }

    @Test
    fun given_blankBikeName_when_creating_thenItIsRejected() =
        runTest {
            val error =
                runCatching {
                    bikeRepository.createBike("   ")
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
        }

    @Test
    fun given_overlongBikeName_when_creating_thenItIsRejected() =
        runTest {
            val error =
                runCatching {
                    bikeRepository.createBike("x".repeat(MAX_GARAGE_NAME_LENGTH + 1))
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(bikeRepository.observeBikes().first().isEmpty())
        }

    @Test
    fun given_bikeNameAtLimit_when_creating_thenItIsStored() =
        runTest {
            val name = "x".repeat(MAX_GARAGE_NAME_LENGTH)

            val id = bikeRepository.createBike(name)

            assertEquals(name, bikeRepository.getBike(id)?.name)
        }

    @Test
    fun given_overlongBikeName_when_updating_thenItIsRejectedAndNameIsKept() =
        runTest {
            val id = bikeRepository.createBike("Gravel")
            val stored = requireNotNull(bikeRepository.getBike(id))

            val error =
                runCatching {
                    bikeRepository.updateBike(stored.copy(name = "x".repeat(MAX_GARAGE_NAME_LENGTH + 1)))
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertEquals("Gravel", bikeRepository.getBike(id)?.name)
        }

    @Test
    fun given_overlongNotes_when_creatingBike_thenItIsRejected() =
        runTest {
            val error =
                runCatching {
                    bikeRepository.createBike("Gravel", notes = "x".repeat(MAX_GARAGE_NOTES_LENGTH + 1))
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(bikeRepository.observeBikes().first().isEmpty())
        }

    @Test
    fun given_notesAtLimit_when_creatingBike_thenTheyAreStoredAndTrimmed() =
        runTest {
            val notes = "  ${"x".repeat(MAX_GARAGE_NOTES_LENGTH)}  "

            val id = bikeRepository.createBike("Gravel", notes = notes)

            assertEquals("x".repeat(MAX_GARAGE_NOTES_LENGTH), bikeRepository.getBike(id)?.notes)
        }

    @Test
    fun given_overlongNotes_when_updatingBike_thenItIsRejectedAndNotesAreKept() =
        runTest {
            val id = bikeRepository.createBike("Gravel", notes = "original")
            val stored = requireNotNull(bikeRepository.getBike(id))

            val error =
                runCatching {
                    bikeRepository.updateBike(stored.copy(notes = "x".repeat(MAX_GARAGE_NOTES_LENGTH + 1)))
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertEquals("original", bikeRepository.getBike(id)?.notes)
        }

    @Test
    fun given_overlongComponentLabel_when_creating_thenItIsRejected() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")

            val error =
                runCatching {
                    componentRepository.createComponent(
                        NewComponent(
                            bikeId = bikeId,
                            type = WearType.CHAIN,
                            label = "x".repeat(MAX_GARAGE_COMPONENT_LABEL_LENGTH + 1),
                            lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                        ),
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(componentRepository.observeComponents(bikeId).first().isEmpty())
        }

    @Test
    fun given_componentLabelAtLimit_when_creating_thenItIsStored() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            val label = "x".repeat(MAX_GARAGE_COMPONENT_LABEL_LENGTH)

            val componentId =
                componentRepository.createComponent(
                    NewComponent(
                        bikeId = bikeId,
                        type = WearType.CHAIN,
                        label = label,
                        lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                    ),
                )

            assertEquals(label, componentRepository.getComponent(componentId)?.label)
        }

    @Test
    fun given_overlongComponentLabel_when_updating_thenItIsRejectedAndLabelIsKept() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            val componentId =
                componentRepository.createComponent(
                    NewComponent(
                        bikeId = bikeId,
                        type = WearType.CHAIN,
                        label = "Chain",
                        lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                    ),
                )
            val stored = requireNotNull(componentRepository.getComponent(componentId))

            val error =
                runCatching {
                    componentRepository.updateComponent(
                        stored.copy(label = "x".repeat(MAX_GARAGE_COMPONENT_LABEL_LENGTH + 1)),
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertEquals("Chain", componentRepository.getComponent(componentId)?.label)
        }

    @Test
    fun given_negativeMeasurement_when_loggingService_thenItIsRejected() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            val componentId =
                componentRepository.createComponent(
                    NewComponent(
                        bikeId = bikeId,
                        type = WearType.CHAIN,
                        label = "Chain",
                        lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                    ),
                )

            val error =
                runCatching {
                    componentRepository.logService(componentId, consumedKm = -1.0, consumedHours = 0.0, wearRatio = 0.0)
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertNull(eventDao.getLastServiceEvent(componentId))
        }

    @Test
    fun given_overlongNotes_when_creatingComponent_thenItIsRejected() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")

            val error =
                runCatching {
                    componentRepository.createComponent(
                        NewComponent(
                            bikeId = bikeId,
                            type = WearType.CHAIN,
                            label = "Chain",
                            lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                            notes = "x".repeat(MAX_GARAGE_NOTES_LENGTH + 1),
                        ),
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(componentRepository.observeComponents(bikeId).first().isEmpty())
        }

    @Test
    fun given_notesAtLimit_when_creatingComponent_thenTheyAreStoredAndTrimmed() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            val notes = "  ${"x".repeat(MAX_GARAGE_NOTES_LENGTH)}  "

            val componentId =
                componentRepository.createComponent(
                    NewComponent(
                        bikeId = bikeId,
                        type = WearType.CHAIN,
                        label = "Chain",
                        lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                        notes = notes,
                    ),
                )

            assertEquals("x".repeat(MAX_GARAGE_NOTES_LENGTH), componentRepository.getComponent(componentId)?.notes)
        }

    @Test
    fun given_overlongNotes_when_loggingService_thenItIsRejectedAndNoEventIsStored() =
        runTest {
            val bikeId = bikeRepository.createBike("Gravel")
            val componentId =
                componentRepository.createComponent(
                    NewComponent(
                        bikeId = bikeId,
                        type = WearType.CHAIN,
                        label = "Chain",
                        lifecycle = WearLifecycle(lifecycleKm = CHAIN_KM),
                    ),
                )

            val error =
                runCatching {
                    componentRepository.logService(
                        componentId = componentId,
                        consumedKm = 0.0,
                        consumedHours = 0.0,
                        wearRatio = 0.0,
                        notes = "x".repeat(MAX_GARAGE_NOTES_LENGTH + 1),
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertNull(eventDao.getLastServiceEvent(componentId))
        }

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

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        const val INSTALL_MILLIS = 1_700_000_000_000L
        const val SERVICE_MILLIS = INSTALL_MILLIS + 10 * MILLIS_PER_DAY
        const val RETIRE_MILLIS = INSTALL_MILLIS + 20 * MILLIS_PER_DAY
        const val DAYS = 10L
        const val SECONDS_PER_HOUR = 3600.0
        const val CHAIN_KM = 2000.0
        const val METERS_PER_KILOMETER = 1000.0
        const val CHAIN_EIGHTY_PERCENT_METERS = CHAIN_KM * 0.8 * METERS_PER_KILOMETER
        const val TIRES_KM = 3000.0
        const val FORK_HOURS = 100.0
    }
}
