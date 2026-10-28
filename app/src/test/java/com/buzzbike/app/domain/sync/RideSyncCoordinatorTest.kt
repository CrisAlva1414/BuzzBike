package com.buzzbike.app.domain.sync

import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [RideSyncCoordinator].
 *
 * The coordinator is the privacy gate for sync: with no configuration it must
 * not even read the ride. No Android, Room or WorkManager is involved.
 */
class RideSyncCoordinatorTest {
    private val dataSource = FakeRideSyncDataSource(input())
    private val enqueuer = RecordingOutboxEnqueuer()
    private val scheduler = RecordingSyncWorkScheduler()
    private val coordinator = RideSyncCoordinator(dataSource, enqueuer, scheduler)

    @Test
    fun given_nullConfig_when_onRideFinished_thenDisabledAndDataNotLoaded() =
        runTest {
            val outcome = coordinator.onRideFinished(RIDE_ID, null)

            assertEquals(RideSyncOutcome.Disabled, outcome)
            assertEquals(0, dataSource.loadCalls)
            assertTrue(enqueuer.enqueued.isEmpty())
            assertTrue(scheduler.configs.isEmpty())
        }

    @Test
    fun given_blankRideId_when_onRideFinished_thenFailed() =
        runTest {
            val outcome = coordinator.onRideFinished("   ", CONFIG)

            assertEquals(RideSyncOutcome.Failed, outcome)
            assertEquals(0, dataSource.loadCalls)
        }

    @Test
    fun given_missingRideData_when_onRideFinished_thenNoData() =
        runTest {
            val missingDataCoordinator =
                RideSyncCoordinator(
                    FakeRideSyncDataSource(null),
                    enqueuer,
                    scheduler,
                )

            val outcome = missingDataCoordinator.onRideFinished(RIDE_ID, CONFIG)

            assertEquals(RideSyncOutcome.NoData, outcome)
            assertTrue(enqueuer.enqueued.isEmpty())
            assertTrue(scheduler.configs.isEmpty())
        }

    @Test
    fun given_validRide_when_onRideFinished_thenEnqueuesPayloadAndSchedules() =
        runTest {
            val outcome = coordinator.onRideFinished(RIDE_ID, CONFIG)

            assertEquals(RideSyncOutcome.Enqueued, outcome)
            assertEquals(1, enqueuer.enqueued.size)
            val (rideId, payload) = enqueuer.enqueued.single()
            assertEquals(RIDE_ID, rideId)
            assertTrue(payload.isNotEmpty())
            assertEquals(listOf(CONFIG), scheduler.configs)
        }

    @Test
    fun given_enqueuerFailure_when_onRideFinished_thenFailedAndNotScheduled() =
        runTest {
            val failingEnqueuer = RecordingOutboxEnqueuer(failure = IllegalStateException("outbox down"))
            val failingCoordinator = RideSyncCoordinator(dataSource, failingEnqueuer, scheduler)

            val outcome = failingCoordinator.onRideFinished(RIDE_ID, CONFIG)

            assertEquals(RideSyncOutcome.Failed, outcome)
            assertTrue(scheduler.configs.isEmpty())
        }

    @Test
    fun given_schedulerFailure_when_onRideFinished_thenFailedButRowStaysEnqueued() =
        runTest {
            val failingScheduler = RecordingSyncWorkScheduler(failure = IllegalStateException("no workmanager"))
            val failingCoordinator = RideSyncCoordinator(dataSource, enqueuer, failingScheduler)

            val outcome = failingCoordinator.onRideFinished(RIDE_ID, CONFIG)

            assertEquals(RideSyncOutcome.Failed, outcome)
            // The payload is already durable; the next worker pass delivers it.
            assertEquals(1, enqueuer.enqueued.size)
        }

    @Test
    fun given_validRide_when_onRideFinished_thenEnqueuedPayloadContainsRideId() =
        runTest {
            coordinator.onRideFinished(RIDE_ID, CONFIG)

            val json = String(enqueuer.enqueued.single().second, Charsets.UTF_8)
            assertTrue(json.contains("\"rideId\":\"$RIDE_ID\""))
            assertFalse(json.isBlank())
        }

    private fun input(rideId: String = RIDE_ID): RideSyncInput =
        RideSyncInput(
            rideId = rideId,
            startTimeMillis = START_MILLIS,
            endTimeMillis = START_MILLIS + DURATION_MILLIS,
            descriptors =
                RideDescriptors(
                    distanceM = 1_000.0,
                    elapsedSeconds = 600L,
                    movingSeconds = 540L,
                    stoppedSeconds = 60L,
                    avgMovingSpeedMps = 5.0,
                    avgSpeedMps = 4.5,
                    maxSpeedMps = 9.0,
                    stopCount = 0,
                    stopDurationsSeconds = emptyList(),
                    acceptedPoints = 1,
                    rejectedPoints = 0,
                    gapCount = 0,
                    distanceIsLowerBound = false,
                    modelVersion = "v1",
                ),
            elevationGainM = 10.0,
            elevationLossM = 5.0,
            caloriesKcal = 200.0,
            caloriesLowKcal = 140.0,
            caloriesHighKcal = 260.0,
            calorieModel = "COMPENDIUM_VELOCITY",
            maxSpeedMps = 9.0,
            points = listOf(TrackPoint(timestampMonotonicNanos = START_NANOS, lat = -33.4, lon = -70.6)),
        )

    private class FakeRideSyncDataSource(
        private val input: RideSyncInput?,
    ) : RideSyncDataSource {
        var loadCalls: Int = 0
            private set

        override suspend fun load(rideId: String): RideSyncInput? {
            loadCalls += 1
            return input
        }
    }

    private class RecordingOutboxEnqueuer(
        private val failure: Exception? = null,
    ) : OutboxEnqueuer {
        val enqueued: MutableList<Pair<String, ByteArray>> = mutableListOf()

        override suspend fun enqueue(
            rideId: String,
            payload: ByteArray,
        ) {
            failure?.let { throw it }
            enqueued += rideId to payload
        }
    }

    private class RecordingSyncWorkScheduler(
        private val failure: Exception? = null,
    ) : SyncWorkScheduler {
        val configs: MutableList<HermesConfig> = mutableListOf()

        override fun schedule(config: HermesConfig) {
            failure?.let { throw it }
            configs += config
        }
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
        private const val START_MILLIS = 1_700_000_000_000L
        private const val DURATION_MILLIS = 600_000L
        private const val START_NANOS = 5_000_000_000L
        private val CONFIG = HermesConfig(endpoint = "https://hermes.example.test/webhook")
    }
}
