package com.buzzbike.app.ui.history

import androidx.lifecycle.SavedStateHandle
import com.buzzbike.app.domain.export.GpxExporter
import com.buzzbike.app.domain.export.GpxRide
import com.buzzbike.app.domain.history.RideDetail
import com.buzzbike.app.domain.history.RideDetailSource
import com.buzzbike.app.domain.history.RideRecord
import com.buzzbike.app.domain.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [RideDetailViewModel].
 *
 * Both ports are in-memory fakes: the source returns a fixture (or throws) and
 * the exporter records the [GpxRide] it receives, so assertions cover exactly
 * the contract the screen will observe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideDetailViewModelTest {
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
    fun given_existingRide_when_created_then_stateIsContentWithPoints() =
        runTest {
            val detail = detail()
            val viewModel = viewModel(RIDE_ID, FakeSource(detail))

            advanceUntilIdle()

            assertEquals(RideDetailUiState.Content(detail), viewModel.state.value)
        }

    @Test
    fun given_unknownRide_when_created_then_stateIsNotFound() =
        runTest {
            val viewModel = viewModel(RIDE_ID, FakeSource(null))

            advanceUntilIdle()

            assertEquals(RideDetailUiState.NotFound, viewModel.state.value)
        }

    @Test
    fun given_missingRideId_when_created_then_stateIsNotFoundWithoutQuerying() =
        runTest {
            val source = FakeSource(detail())
            val viewModel = viewModel(rideId = null, source = source)

            advanceUntilIdle()

            assertEquals(RideDetailUiState.NotFound, viewModel.state.value)
            assertEquals(0, source.calls)
        }

    @Test
    fun given_failingSource_when_created_then_stateIsErrorWithStaticMessage() =
        runTest {
            val viewModel =
                viewModel(
                    RIDE_ID,
                    FakeSource(detail(), failure = IllegalStateException("boom")),
                )

            advanceUntilIdle()

            assertEquals(RideDetailUiState.Error("No se pudo cargar el ride."), viewModel.state.value)
        }

    @Test
    fun given_loadedRide_when_exportSucceeds_then_rideIsHandedToExporter() =
        runTest {
            val detail = detail()
            val exporter = FakeExporter(Result.success(Unit))
            val viewModel = viewModel(RIDE_ID, FakeSource(detail), exporter)
            advanceUntilIdle()

            viewModel.exportGpx(DESTINATION)
            assertEquals(GpxExportState.Exporting, viewModel.exportState.value)
            advanceUntilIdle()

            assertEquals(GpxExportState.Success, viewModel.exportState.value)
            assertEquals(DESTINATION, exporter.lastDestination)
            val ride = requireNotNull(exporter.lastRide)
            assertEquals(RideHistoryFormat.rideTitle(detail.ride.startTimeMillis), ride.name)
            assertEquals(detail.ride.startTimeMillis, ride.startTimeMillis)
            assertEquals(detail.points, ride.points)
        }

    @Test
    fun given_emptyPoints_when_exportSucceeds_then_rideHasNoPointsAndStateIsSuccess() =
        runTest {
            val exporter = FakeExporter(Result.success(Unit))
            val detail = detail(points = emptyList())
            val viewModel = viewModel(RIDE_ID, FakeSource(detail), exporter)
            advanceUntilIdle()

            viewModel.exportGpx(DESTINATION)
            advanceUntilIdle()

            assertEquals(GpxExportState.Success, viewModel.exportState.value)
            val ride = requireNotNull(exporter.lastRide)
            assertEquals(detail.ride.startTimeMillis, ride.startTimeMillis)
            assertTrue(ride.points.isEmpty())
        }

    @Test
    fun given_exportSucceeded_when_resetExportState_then_stateIsIdle() =
        runTest {
            val viewModel = viewModel(RIDE_ID, FakeSource(detail()), FakeExporter(Result.success(Unit)))
            advanceUntilIdle()

            viewModel.exportGpx(DESTINATION)
            advanceUntilIdle()
            viewModel.resetExportState()

            assertEquals(GpxExportState.Idle, viewModel.exportState.value)
            assertEquals(RideDetailUiState.Content(detail()), viewModel.state.value)
        }

    @Test
    fun given_loadedRide_when_exportFails_then_errorWithStaticMessage() =
        runTest {
            val viewModel =
                viewModel(
                    RIDE_ID,
                    FakeSource(detail()),
                    FakeExporter(Result.failure(IllegalStateException("disk"))),
                )
            advanceUntilIdle()

            viewModel.exportGpx(DESTINATION)
            advanceUntilIdle()

            assertEquals(GpxExportState.Error("No se pudo exportar el GPX."), viewModel.exportState.value)
        }

    @Test
    fun given_noContent_when_exportRequested_then_exporterIsNotCalledAndStateStaysIdle() =
        runTest {
            val exporter = FakeExporter(Result.success(Unit))
            val viewModel = viewModel(RIDE_ID, FakeSource(null), exporter)
            advanceUntilIdle()

            viewModel.exportGpx(DESTINATION)
            advanceUntilIdle()

            assertEquals(GpxExportState.Idle, viewModel.exportState.value)
            assertEquals(0, exporter.calls)
            assertNull(exporter.lastDestination)
        }

    private fun viewModel(
        rideId: String?,
        source: RideDetailSource,
        exporter: GpxExporter = FakeExporter(Result.success(Unit)),
    ): RideDetailViewModel {
        val handle = if (rideId == null) SavedStateHandle() else SavedStateHandle(mapOf("rideId" to rideId))
        return RideDetailViewModel(handle, source, exporter)
    }

    private fun detail(
        rideId: String = RIDE_ID,
        points: List<TrackPoint> = defaultPoints(),
    ): RideDetail =
        RideDetail(
            ride =
                RideRecord(
                    id = rideId,
                    startTimeMillis = START_MILLIS,
                    endTimeMillis = START_MILLIS + 1_800_000L,
                    distanceM = 12_350.0,
                    movingTimeSeconds = 2_700L,
                    stoppedTimeSeconds = 0L,
                    elevationGainM = 120.0,
                    elevationLossM = 80.0,
                    avgSpeedMps = 4.57,
                    maxSpeedMps = 11.2,
                    calories = null,
                ),
            points = points,
        )

    private fun defaultPoints(): List<TrackPoint> =
        listOf(
            TrackPoint(timestampMonotonicNanos = 1_000_000L, lat = -33.45, lon = -70.66),
            TrackPoint(timestampMonotonicNanos = 2_000_000L, lat = -33.46, lon = -70.67),
        )

    private class FakeSource(
        private val detail: RideDetail?,
        private val failure: Throwable? = null,
    ) : RideDetailSource {
        var calls = 0

        override suspend fun getRideDetail(rideId: String): RideDetail? {
            calls++
            failure?.let { throw it }
            return detail
        }
    }

    private class FakeExporter(
        private val result: Result<Unit>,
    ) : GpxExporter {
        var calls = 0
        var lastDestination: String? = null
        var lastRide: GpxRide? = null

        override suspend fun export(
            destination: String,
            ride: GpxRide,
        ): Result<Unit> {
            calls++
            lastDestination = destination
            lastRide = ride
            return result
        }
    }

    private companion object {
        const val RIDE_ID = "r1"
        const val START_MILLIS = 1_790_000_000_000L
        const val DESTINATION = "content://com.buzzbike.test/export/r1.gpx"
    }
}
