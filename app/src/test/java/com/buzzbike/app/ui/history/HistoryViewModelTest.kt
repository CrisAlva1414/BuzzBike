package com.buzzbike.app.ui.history

import app.cash.turbine.test
import com.buzzbike.app.domain.history.RideHistorySource
import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.history.WeeklySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [HistoryViewModel].
 *
 * The source is an in-memory [MutableStateFlow], so every emission is explicit
 * and the assertions check exactly what the UI would render.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
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
    fun given_emptySource_when_collected_then_loadingThenEmptyWithZeroWeek() =
        runTest {
            val viewModel = HistoryViewModel(MutableSource())

            viewModel.state.test {
                assertEquals(HistoryUiState.Loading, awaitItem())
                val state = awaitItem()
                assertTrue(state is HistoryUiState.Empty)
                assertEquals(
                    WeeklySummary(distanceM = 0.0, rideCount = 0),
                    (state as HistoryUiState.Empty).weekly,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun given_ridesInCurrentWeek_when_collected_then_loadingThenContent() =
        runTest {
            val rides = listOf(ride(startMillis = System.currentTimeMillis(), distanceM = 12_350.0))
            val viewModel = HistoryViewModel(MutableSource(MutableStateFlow(rides)))

            viewModel.state.test {
                assertEquals(HistoryUiState.Loading, awaitItem())
                val state = awaitItem()
                assertTrue(state is HistoryUiState.Content)
                val content = state as HistoryUiState.Content
                assertEquals(rides, content.rides)
                assertEquals(1, content.weekly.rideCount)
                assertEquals(12_350.0, content.weekly.distanceM, DISTANCE_DELTA)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun given_failingSource_when_collected_then_loadingThenError() =
        runTest {
            val viewModel = HistoryViewModel(FailingSource())

            viewModel.state.test {
                assertEquals(HistoryUiState.Loading, awaitItem())
                assertEquals(
                    HistoryUiState.Error("No se pudo cargar el historial."),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun given_sourceEmitsAgain_when_collected_then_stateReflectsLatestList() =
        runTest {
            val now = System.currentTimeMillis()
            val first = listOf(ride(startMillis = now, distanceM = 1_000.0))
            val second = listOf(ride(startMillis = now + 1, distanceM = 2_000.0))
            val source = MutableSource(MutableStateFlow(first))
            val viewModel = HistoryViewModel(source)

            viewModel.state.test {
                assertEquals(HistoryUiState.Loading, awaitItem())
                assertEquals(first, (awaitItem() as HistoryUiState.Content).rides)
                source.rides.value = second
                assertEquals(second, (awaitItem() as HistoryUiState.Content).rides)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private class MutableSource(
        val rides: MutableStateFlow<List<RideRecord>> = MutableStateFlow(emptyList()),
    ) : RideHistorySource {
        override fun observeCompletedRides(): Flow<List<RideRecord>> = rides
    }

    private class FailingSource : RideHistorySource {
        override fun observeCompletedRides(): Flow<List<RideRecord>> =
            flow {
                throw IllegalStateException("boom")
            }
    }

    private fun ride(
        startMillis: Long,
        distanceM: Double,
    ): RideRecord =
        RideRecord(
            id = "ride-$startMillis",
            startTimeMillis = startMillis,
            endTimeMillis = null,
            distanceM = distanceM,
            movingTimeSeconds = 0L,
            stoppedTimeSeconds = 0L,
            elevationGainM = 0.0,
            elevationLossM = 0.0,
            avgSpeedMps = 0.0,
            maxSpeedMps = 0.0,
            calories = null,
        )

    private companion object {
        const val DISTANCE_DELTA = 0.001
    }
}
