package com.buzzbike.app.ui.settings

import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.calories.UserProfileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [ProfileViewModel].
 *
 * The store is an in-memory fake, so the assertions inspect exactly what would
 * be persisted. Personal values are only checked through the state the user
 * sees; nothing here asserts on logs because the view model never logs them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
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
    fun given_storedProfile_when_created_then_formIsPrefilledAndComplete() =
        runTest {
            val store = FakeUserProfileStore(UserProfile(70.0, 175.0, 30, isMale = true))

            val viewModel = ProfileViewModel(store, dispatcher)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("70.0", state.weight)
            assertEquals("175.0", state.height)
            assertEquals("30", state.age)
            assertEquals(true, state.isMale)
            assertTrue(state.isComplete)
            assertTrue(state.hasProfile)
            assertFalse(state.hasChanges)
            assertFalse(state.canSave)
        }

    @Test
    fun given_outOfRangeValues_when_edited_then_fieldErrorsAndSaveIsBlocked() =
        runTest {
            val store = FakeUserProfileStore()
            val viewModel = ProfileViewModel(store, dispatcher)
            advanceUntilIdle()

            viewModel.onWeightChange("500")
            viewModel.onHeightChange("50")
            viewModel.onAgeChange("200")
            viewModel.onSexChange(true)

            val state = viewModel.uiState.value
            assertEquals(WEIGHT_RANGE_MESSAGE, state.weightError)
            assertEquals(HEIGHT_RANGE_MESSAGE, state.heightError)
            assertEquals(AGE_RANGE_MESSAGE, state.ageError)
            assertFalse(state.isComplete)
            assertFalse(state.canSave)

            viewModel.save()
            advanceUntilIdle()

            assertTrue("invalid form must never reach the store", store.saved.isEmpty())
            assertNull("a plain validation failure is not a storage error", state.error)
        }

    @Test
    fun given_nonNumericInput_when_edited_thenInvalidMessageIsShown() =
        runTest {
            val viewModel = ProfileViewModel(FakeUserProfileStore(), dispatcher)
            advanceUntilIdle()

            viewModel.onWeightChange("setenta")

            assertEquals(INVALID_WEIGHT_MESSAGE, viewModel.uiState.value.weightError)
            assertFalse(viewModel.uiState.value.canSave)
        }

    @Test
    fun given_validForm_when_saved_thenProfileIsPersisted() =
        runTest {
            val store = FakeUserProfileStore()
            val viewModel = ProfileViewModel(store, dispatcher)
            advanceUntilIdle()

            viewModel.onWeightChange("70")
            viewModel.onHeightChange("175")
            viewModel.onAgeChange("30")
            viewModel.onSexChange(true)
            assertTrue(viewModel.uiState.value.canSave)

            viewModel.save()
            advanceUntilIdle()

            assertEquals(
                UserProfile(weightKg = 70.0, heightCm = 175.0, ageYears = 30, isMale = true),
                store.stored,
            )
            val state = viewModel.uiState.value
            assertTrue(state.saved)
            assertTrue(state.hasProfile)
            assertTrue(state.isComplete)
            assertFalse(state.hasChanges)
            assertFalse(state.canSave)
        }

    @Test
    fun given_savedProfile_when_editedAgain_thenSaveIsEnabledAgain() =
        runTest {
            val store = FakeUserProfileStore(UserProfile(70.0, 175.0, 30, isMale = true))
            val viewModel = ProfileViewModel(store, dispatcher)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.canSave)

            viewModel.onAgeChange("31")

            assertTrue(viewModel.uiState.value.hasChanges)
            assertTrue(viewModel.uiState.value.canSave)
        }

    @Test
    fun given_storedProfile_when_cleared_thenFormIsEmptyAndStoreIsEmpty() =
        runTest {
            val store = FakeUserProfileStore(UserProfile(70.0, 175.0, 30, isMale = false))
            val viewModel = ProfileViewModel(store, dispatcher)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasProfile)

            viewModel.clear()
            advanceUntilIdle()

            assertNull(store.stored)
            assertTrue(store.clearCalls > 0)
            val state = viewModel.uiState.value
            assertEquals("", state.weight)
            assertEquals("", state.height)
            assertEquals("", state.age)
            assertNull(state.isMale)
            assertFalse(state.hasProfile)
            assertFalse(state.isComplete)
            assertFalse(state.canSave)
        }

    @Test
    fun given_fourFields_when_derived_thenProfileIsComplete() =
        runTest {
            val viewModel = ProfileViewModel(FakeUserProfileStore(), dispatcher)
            advanceUntilIdle()

            viewModel.onWeightChange("80")
            viewModel.onHeightChange("180")
            viewModel.onAgeChange("40")
            viewModel.onSexChange(false)

            assertTrue(viewModel.uiState.value.isComplete)
        }

    @Test
    fun given_threeFieldsWithoutSex_when_derived_thenProfileIsIncomplete() =
        runTest {
            val viewModel = ProfileViewModel(FakeUserProfileStore(), dispatcher)
            advanceUntilIdle()

            viewModel.onWeightChange("80")
            viewModel.onHeightChange("180")
            viewModel.onAgeChange("40")

            assertFalse(viewModel.uiState.value.isComplete)
        }

    @Test
    fun given_blankProfile_when_created_thenNothingToSave() =
        runTest {
            val viewModel = ProfileViewModel(FakeUserProfileStore(), dispatcher)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.hasChanges)
            assertFalse(viewModel.uiState.value.canSave)
            assertFalse(viewModel.uiState.value.hasProfile)
        }

    @Test
    fun given_saveInFlight_when_cleared_thenStoreEndsEmptyAndStateIsCoherent() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val store = FakeUserProfileStore().apply { saveGate = gate }
            val viewModel = ProfileViewModel(store, dispatcher)
            advanceUntilIdle()

            viewModel.onWeightChange("70")
            viewModel.onHeightChange("175")
            viewModel.onAgeChange("30")
            viewModel.onSexChange(true)
            viewModel.save()
            runCurrent()
            assertTrue(viewModel.uiState.value.isBusy)

            viewModel.clear()
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull("clear must win over the in-flight save", store.stored)
            val state = viewModel.uiState.value
            assertFalse(state.hasProfile)
            assertFalse(state.isBusy)
            assertFalse(state.clearing)
            assertFalse(state.saved)
            assertNull(state.error)
        }

    @Test
    fun given_storageFails_when_saved_thenErrorIsShownAndStateIsNotSaved() =
        runTest {
            val store = FakeUserProfileStore().apply { failSaves = true }
            val viewModel = ProfileViewModel(store, dispatcher)
            advanceUntilIdle()

            viewModel.onWeightChange("70")
            viewModel.onHeightChange("175")
            viewModel.onAgeChange("30")
            viewModel.onSexChange(true)
            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNotNull(state.error)
            assertFalse(state.saved)
            assertFalse(state.isBusy)
        }

    private class FakeUserProfileStore(
        initial: UserProfile? = null,
    ) : UserProfileStore {
        private val flow = MutableStateFlow(initial)
        val saved = mutableListOf<UserProfile>()
        var stored: UserProfile? = initial
            private set
        var clearCalls = 0
        var failSaves = false
        var saveGate: CompletableDeferred<Unit>? = null

        override fun observe(): Flow<UserProfile?> = flow

        override suspend fun get(): UserProfile? = stored

        override suspend fun save(profile: UserProfile) {
            saveGate?.await()
            if (failSaves) error("storage unavailable")
            stored = profile
            saved += profile
            flow.value = profile
        }

        override suspend fun clear() {
            stored = null
            flow.value = null
            clearCalls += 1
        }
    }
}
