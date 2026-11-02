package com.buzzbike.app.ui.garage

import app.cash.turbine.test
import com.buzzbike.app.domain.wear.Bike
import com.buzzbike.app.domain.wear.BikeComponent
import com.buzzbike.app.domain.wear.BikeRepository
import com.buzzbike.app.domain.wear.ComponentRepository
import com.buzzbike.app.domain.wear.ComponentUsage
import com.buzzbike.app.domain.wear.ComponentWear
import com.buzzbike.app.domain.wear.MAX_GARAGE_NOTES_LENGTH
import com.buzzbike.app.domain.wear.NewComponent
import com.buzzbike.app.domain.wear.WearEngine
import com.buzzbike.app.domain.wear.WearLifecycle
import com.buzzbike.app.domain.wear.WearStatus
import com.buzzbike.app.domain.wear.WearType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [GarageViewModel].
 *
 * Both ports are in-memory fakes, so every assertion checks exactly what would
 * be persisted. The component fake runs the real [WearEngine] on the configured
 * lifecycle, which keeps these tests honest about the 80/100 thresholds without
 * duplicating the formula.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GarageViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun given_emptyGarage_when_collected_then_loadingThenEmpty() =
        runTest {
            val viewModel = GarageViewModel(FakeBikeRepository(), FakeComponentRepository())

            viewModel.state.test {
                assertEquals(GarageUiState.Loading, awaitItem())
                assertEquals(GarageUiState.Empty(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun given_activeAndArchivedBikes_when_collected_thenFirstActiveIsSelectedAndWearIsAssessed() =
        runTest {
            val active = bike(id = "active", name = "Gravel")
            val archived = bike(id = "archived", name = "Old", archived = true)
            val bikes = FakeBikeRepository(listOf(active, archived))
            val components = FakeComponentRepository()
            components.components.value =
                listOf(
                    component(
                        id = "chain",
                        bikeId = active.id,
                        lifecycle = WearLifecycle(lifecycleKm = 2000.0),
                    ),
                )
            components.usage["chain"] = ComponentUsage(1600.0, 0.0, 0.0)
            val viewModel = GarageViewModel(bikes, components)
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            val content = viewModel.state.value as GarageUiState.Content
            assertEquals("active", content.selectedBikeId)
            assertEquals(2, content.bikes.size)
            assertEquals(WearStatus.APPROACHING, content.selected?.alertStatus)
            assertEquals(
                0.8,
                content.selected
                    ?.components
                    ?.single()
                    ?.assessment
                    ?.ratio ?: -1.0,
                DELTA,
            )
        }

    @Test
    fun given_twoBikes_when_selectingSecond_thenContentFollowsSelection() =
        runTest {
            val first = bike(id = "first", name = "Gravel")
            val second = bike(id = "second", name = "Road")
            val viewModel = GarageViewModel(FakeBikeRepository(listOf(first, second)), FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.selectBike(second.id)
            advanceUntilIdle()

            assertEquals(second.id, (viewModel.state.value as GarageUiState.Content).selectedBikeId)
        }

    @Test
    fun given_blankBikeName_when_created_thenFormShowsErrorAndNothingIsPersisted() =
        runTest {
            val bikes = FakeBikeRepository()
            val viewModel = GarageViewModel(bikes, FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.CreateBike)
            viewModel.updateForm((viewModel.form.value as GarageForm.Bike).copy(name = "   "))
            viewModel.submitForm()
            advanceUntilIdle()

            assertEquals(BIKE_NAME_REQUIRED_MESSAGE, (viewModel.form.value as GarageForm.Bike).nameError)
            assertTrue(bikes.createdNames.isEmpty())
        }

    @Test
    fun given_nameOver40Chars_when_created_thenFormShowsLengthError() =
        runTest {
            val bikes = FakeBikeRepository()
            val viewModel = GarageViewModel(bikes, FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.CreateBike)
            viewModel.updateForm((viewModel.form.value as GarageForm.Bike).copy(name = "x".repeat(41)))
            viewModel.submitForm()
            advanceUntilIdle()

            assertEquals(BIKE_NAME_TOO_LONG_MESSAGE, (viewModel.form.value as GarageForm.Bike).nameError)
            assertTrue(bikes.createdNames.isEmpty())
        }

    @Test
    fun given_validName_when_created_thenBikeIsPersistedAndSelected() =
        runTest {
            val bikes = FakeBikeRepository()
            val viewModel = GarageViewModel(bikes, FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.CreateBike)
            viewModel.updateForm((viewModel.form.value as GarageForm.Bike).copy(name = "  Gravel  "))
            viewModel.submitForm()
            advanceUntilIdle()

            val created = bikes.bikes.value.single()
            assertEquals("Gravel", created.name)
            assertNull(viewModel.form.value)
            assertEquals(created.id, (viewModel.state.value as GarageUiState.Content).selectedBikeId)
        }

    @Test
    fun given_existingBike_when_renamed_thenRepositoryKeepsTrimmedName() =
        runTest {
            val bikes = FakeBikeRepository(listOf(bike(id = "bike-1", name = "Gravel")))
            val viewModel = GarageViewModel(bikes, FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.RenameBike("bike-1"))
            assertEquals("Gravel", (viewModel.form.value as GarageForm.Bike).name)
            viewModel.updateForm((viewModel.form.value as GarageForm.Bike).copy(name = "  Road  "))
            viewModel.submitForm()
            advanceUntilIdle()

            assertEquals(
                "Road",
                bikes.bikes.value
                    .single()
                    .name,
            )
        }

    @Test
    fun given_existingBike_when_archived_thenFlagIsPersisted() =
        runTest {
            val bikes = FakeBikeRepository(listOf(bike(id = "bike-1", name = "Gravel")))
            val viewModel = GarageViewModel(bikes, FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.setBikeArchived("bike-1", archived = true)
            advanceUntilIdle()

            assertTrue(
                bikes.bikes.value
                    .single()
                    .isArchived,
            )
        }

    @Test
    fun given_existingBike_when_deleted_thenItLeavesTheListAndStateIsEmpty() =
        runTest {
            val bikes = FakeBikeRepository(listOf(bike(id = "bike-1", name = "Gravel")))
            val viewModel = GarageViewModel(bikes, FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.deleteBike("bike-1")
            advanceUntilIdle()

            assertTrue(bikes.bikes.value.isEmpty())
            assertTrue(viewModel.state.value is GarageUiState.Empty)
        }

    @Test
    fun given_chainType_when_componentFormOpened_thenDefaultsArePrefilledEditable() =
        runTest {
            val bikes = FakeBikeRepository(listOf(bike(id = "bike-1", name = "Gravel")))
            val components = FakeComponentRepository()
            val viewModel = GarageViewModel(bikes, components)
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.CreateComponent("bike-1"))
            val form = viewModel.form.value as GarageForm.Component

            assertEquals(WearType.CHAIN, form.type)
            assertEquals("2000", form.lifecycleKm)
            assertEquals("", form.lifecycleHours)

            viewModel.updateForm(form.copy(label = "Cadena nueva", lifecycleKm = "2500"))
            viewModel.submitForm()
            advanceUntilIdle()

            val created = components.components.value.single()
            assertEquals("Cadena nueva", created.label)
            assertEquals(2500.0, created.lifecycle?.lifecycleKm ?: -1.0, DELTA)
        }

    @Test
    fun given_brakesWithoutLifecycle_when_submitted_thenErrorAndNothingIsPersisted() =
        runTest {
            val bikes = FakeBikeRepository(listOf(bike(id = "bike-1", name = "Gravel")))
            val components = FakeComponentRepository()
            val viewModel = GarageViewModel(bikes, components)
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.CreateComponent("bike-1"))
            val chainForm = viewModel.form.value as GarageForm.Component
            viewModel.updateForm(componentFormWithType(chainForm, WearType.BRAKES).copy(label = "Frenos"))
            viewModel.submitForm()
            advanceUntilIdle()

            assertEquals(
                LIFECYCLE_REQUIRED_MESSAGE,
                (viewModel.form.value as GarageForm.Component).lifecycleError,
            )
            assertTrue(components.components.value.isEmpty())
        }

    @Test
    fun given_blankComponentLabel_when_submitted_thenErrorAndNothingIsPersisted() =
        runTest {
            val bikes = FakeBikeRepository(listOf(bike(id = "bike-1", name = "Gravel")))
            val components = FakeComponentRepository()
            val viewModel = GarageViewModel(bikes, components)
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.CreateComponent("bike-1"))
            viewModel.submitForm()
            advanceUntilIdle()

            assertEquals(
                COMPONENT_LABEL_REQUIRED_MESSAGE,
                (viewModel.form.value as GarageForm.Component).labelError,
            )
            assertTrue(components.components.value.isEmpty())
        }

    @Test
    fun given_componentWithUsage_when_serviceFormOpened_thenKmAndHoursArePrefilled() =
        runTest {
            val bike = bike(id = "bike-1", name = "Gravel")
            val bikes = FakeBikeRepository(listOf(bike))
            val components = FakeComponentRepository()
            components.components.value =
                listOf(
                    component(
                        id = "chain",
                        bikeId = bike.id,
                        lifecycle = WearLifecycle(lifecycleKm = 2000.0),
                    ),
                )
            components.usage["chain"] = ComponentUsage(123.4, 2.5, 3.0)
            val viewModel = GarageViewModel(bikes, components)
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.ServiceComponent("chain"))
            val form = viewModel.form.value as GarageForm.Service

            assertEquals("123.4", form.km)
            assertEquals("2.5", form.hours)
            assertEquals(123.4 / 2000.0, form.wearRatio ?: -1.0, DELTA)
        }

    @Test
    fun given_openServiceForm_when_submitted_thenEventReceivesValuesAndTrimmedNotes() =
        runTest {
            val bike = bike(id = "bike-1", name = "Gravel")
            val bikes = FakeBikeRepository(listOf(bike))
            val components = FakeComponentRepository()
            components.components.value =
                listOf(
                    component(
                        id = "chain",
                        bikeId = bike.id,
                        lifecycle = WearLifecycle(lifecycleKm = 2000.0),
                    ),
                )
            components.usage["chain"] = ComponentUsage(123.4, 2.5, 3.0)
            val viewModel = GarageViewModel(bikes, components)
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.ServiceComponent("chain"))
            val form = viewModel.form.value as GarageForm.Service
            viewModel.updateForm(form.copy(km = "150", hours = "3.5", notes = "  cadena nueva  "))
            viewModel.submitForm()
            advanceUntilIdle()

            val call = components.services.single()
            assertEquals("chain", call.componentId)
            assertEquals(150.0, call.consumedKm ?: -1.0, DELTA)
            assertEquals(3.5, call.consumedHours ?: -1.0, DELTA)
            assertEquals(123.4 / 2000.0, call.wearRatio ?: -1.0, DELTA)
            assertEquals("cadena nueva", call.notes)
        }

    @Test
    fun given_overlongServiceNotes_when_submitted_thenNotesErrorIsShownAndNothingIsLogged() =
        runTest {
            val bike = bike(id = "bike-1", name = "Gravel")
            val bikes = FakeBikeRepository(listOf(bike))
            val components = FakeComponentRepository()
            components.components.value =
                listOf(
                    component(
                        id = "chain",
                        bikeId = bike.id,
                        lifecycle = WearLifecycle(lifecycleKm = 2000.0),
                    ),
                )
            val viewModel = GarageViewModel(bikes, components)
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.ServiceComponent("chain"))
            val form = viewModel.form.value as GarageForm.Service
            viewModel.updateForm(form.copy(notes = "x".repeat(MAX_GARAGE_NOTES_LENGTH + 1)))
            viewModel.submitForm()
            advanceUntilIdle()

            assertEquals(
                SERVICE_NOTES_TOO_LONG_MESSAGE,
                (viewModel.form.value as GarageForm.Service).notesError,
            )
            assertTrue(components.services.isEmpty())
        }

    @Test
    fun given_component_when_retired_thenItIsRemovedFromTheAssessment() =
        runTest {
            val bike = bike(id = "bike-1", name = "Gravel")
            val bikes = FakeBikeRepository(listOf(bike))
            val components = FakeComponentRepository()
            components.components.value =
                listOf(component(id = "chain", bikeId = bike.id, lifecycle = WearLifecycle(lifecycleKm = 2000.0)))
            val viewModel = GarageViewModel(bikes, components)
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.retireComponent("chain")
            advanceUntilIdle()

            assertEquals(listOf("chain"), components.retired)
            assertTrue((viewModel.state.value as GarageUiState.Content).selected?.components?.isEmpty() == true)
        }

    @Test
    fun given_writeInFlight_when_submittedAgain_thenItIsIgnoredAndBusyIsExposed() =
        runTest {
            val bikes = FakeBikeRepository(listOf(bike(id = "bike-1", name = "Gravel")))
            val gate = CompletableDeferred<Unit>()
            bikes.createGate = gate
            val viewModel = GarageViewModel(bikes, FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.CreateBike)
            viewModel.updateForm((viewModel.form.value as GarageForm.Bike).copy(name = "Second"))
            viewModel.submitForm()
            runCurrent()

            assertTrue((viewModel.state.value as GarageUiState.Content).isBusy)
            viewModel.submitForm()
            runCurrent()
            assertEquals(1, bikes.createdNames.size)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, bikes.createdNames.size)
            assertFalse((viewModel.state.value as GarageUiState.Content).isBusy)
        }

    @Test
    fun given_repositoryFailure_when_creating_thenErrorIsShownAndBusyIsCleared() =
        runTest {
            val bikes = FakeBikeRepository(listOf(bike(id = "bike-1", name = "Gravel")))
            bikes.failCreates = true
            val viewModel = GarageViewModel(bikes, FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.CreateBike)
            viewModel.updateForm((viewModel.form.value as GarageForm.Bike).copy(name = "New"))
            viewModel.submitForm()
            advanceUntilIdle()

            val content = viewModel.state.value as GarageUiState.Content
            assertFalse(content.isBusy)
            assertEquals("No se pudo guardar el cambio; reintentá.", content.message)
        }

    @Test
    fun given_openForm_when_closed_thenFormIsDiscarded() =
        runTest {
            val viewModel = GarageViewModel(FakeBikeRepository(), FakeComponentRepository())
            backgroundScope.launch { viewModel.state.collect() }
            advanceUntilIdle()

            viewModel.openForm(GarageFormTarget.CreateBike)
            assertTrue(viewModel.form.value is GarageForm.Bike)

            viewModel.closeForm()

            assertNull(viewModel.form.value)
        }

    private fun bike(
        id: String,
        name: String,
        archived: Boolean = false,
    ): Bike = Bike(id = id, name = name, notes = null, createdAtMillis = 0, isArchived = archived)

    private fun component(
        id: String,
        bikeId: String,
        lifecycle: WearLifecycle?,
        type: WearType = WearType.CHAIN,
    ): BikeComponent =
        BikeComponent(
            id = id,
            bikeId = bikeId,
            type = type,
            label = "Componente",
            installedAtMillis = 0,
            lifecycle = lifecycle,
            retiredAtMillis = null,
            notes = null,
        )

    private class FakeBikeRepository(
        initial: List<Bike> = emptyList(),
    ) : BikeRepository {
        val bikes = MutableStateFlow(initial)
        val createdNames = mutableListOf<String>()
        var createGate: CompletableDeferred<Unit>? = null
        var failCreates = false
        private var nextId = initial.size + 1

        override fun observeBikes(): Flow<List<Bike>> = bikes

        override suspend fun getBike(id: String): Bike? = bikes.value.firstOrNull { it.id == id }

        override suspend fun createBike(
            name: String,
            notes: String?,
        ): String {
            createdNames += name
            createGate?.await()
            if (failCreates) error("storage unavailable")
            val id = "bike-${nextId++}"
            bikes.value =
                bikes.value +
                Bike(
                    id = id,
                    name = name,
                    notes = notes,
                    createdAtMillis = 0,
                    isArchived = false,
                )
            return id
        }

        override suspend fun updateBike(bike: Bike) {
            bikes.value = bikes.value.map { if (it.id == bike.id) bike else it }
        }

        override suspend fun deleteBike(id: String) {
            bikes.value = bikes.value.filterNot { it.id == id }
        }

        override suspend fun assignRideToBike(
            rideId: String,
            bikeId: String?,
        ) = Unit
    }

    private class FakeComponentRepository : ComponentRepository {
        val components = MutableStateFlow<List<BikeComponent>>(emptyList())
        val usage = mutableMapOf<String, ComponentUsage>()
        val services = mutableListOf<ServiceCall>()
        val retired = mutableListOf<String>()
        private var nextId = 1

        override fun observeComponents(bikeId: String): Flow<List<BikeComponent>> =
            components.map { list -> list.filter { it.bikeId == bikeId && !it.isRetired } }

        override suspend fun getComponent(id: String): BikeComponent? = components.value.firstOrNull { it.id == id }

        override suspend fun createComponent(draft: NewComponent): String {
            val id = "component-${nextId++}"
            components.value =
                components.value +
                BikeComponent(
                    id = id,
                    bikeId = draft.bikeId,
                    type = draft.type,
                    label = draft.label,
                    installedAtMillis = draft.installedAtMillis ?: 0,
                    lifecycle = draft.lifecycle,
                    retiredAtMillis = null,
                    notes = draft.notes,
                )
            return id
        }

        override suspend fun updateComponent(component: BikeComponent) {
            components.value = components.value.map { if (it.id == component.id) component else it }
        }

        override suspend fun retireComponent(
            id: String,
            retiredAtMillis: Long?,
        ) {
            retired += id
            components.value =
                components.value.map {
                    if (it.id == id) it.copy(retiredAtMillis = retiredAtMillis ?: 1L) else it
                }
        }

        override suspend fun logService(
            componentId: String,
            consumedKm: Double?,
            consumedHours: Double?,
            wearRatio: Double?,
            notes: String?,
        ) {
            services += ServiceCall(componentId, consumedKm, consumedHours, wearRatio, notes)
        }

        override suspend fun assessWear(bikeId: String): List<ComponentWear> =
            components.value
                .filter { it.bikeId == bikeId && !it.isRetired }
                .map { component ->
                    val used = usage[component.id] ?: ComponentUsage(0.0, 0.0, 0.0)
                    ComponentWear(
                        component = component,
                        usage = used,
                        assessment = WearEngine.assess(component.lifecycle, used),
                        baselineMillis = 0,
                    )
                }
    }

    private data class ServiceCall(
        val componentId: String,
        val consumedKm: Double?,
        val consumedHours: Double?,
        val wearRatio: Double?,
        val notes: String?,
    )

    private companion object {
        const val DELTA = 1e-9
    }
}
