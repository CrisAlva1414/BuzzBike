package com.buzzbike.app.domain.sync

import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM-pure tests for [RideSyncCoordinator].
 *
 * The coordinator is the privacy gate for sync: with no configuration or no
 * stored secret it must not even read the ride. No Android, Room or
 * WorkManager is involved.
 */
class RideSyncCoordinatorTest {
    private val dataSource = FakeRideSyncDataSource(input())
    private val enqueuer = RecordingOutboxEnqueuer()
    private val scheduler = RecordingSyncWorkScheduler()
    private val configStore = FakeSyncConfigStore(CONFIG)
    private val secretStorage = FakeSecretStorage(byteArrayOf(1))
    private val coordinator = coordinator()

    @Test
    fun given_nullConfig_when_onRideFinished_thenDisabledAndDataNotLoaded() =
        runTest {
            configStore.config = null

            val outcome = coordinator.onRideFinished(RIDE_ID)

            assertEquals(RideSyncOutcome.Disabled, outcome)
            assertEquals(0, dataSource.loadCalls)
            assertTrue(enqueuer.enqueued.isEmpty())
            assertTrue(scheduler.configs.isEmpty())
        }

    @Test
    fun given_missingSecret_when_onRideFinished_thenDisabledAndDataNotLoaded() =
        runTest {
            secretStorage.clear()

            val outcome = coordinator.onRideFinished(RIDE_ID)

            assertEquals(RideSyncOutcome.Disabled, outcome)
            assertEquals(0, dataSource.loadCalls)
            assertTrue(enqueuer.enqueued.isEmpty())
            assertTrue(scheduler.configs.isEmpty())
        }

    @Test
    fun given_disconnectWinsSharedGate_when_coordinatorResumes_thenDisabledAndNothingEnqueued() =
        runBlocking {
            val gate = SyncMutationGate()
            val gatekeeper =
                SyncGatekeeper(
                    configStore = configStore,
                    secretManager = HmacSecretManager(IdentitySecretSealer, secretStorage),
                    gate = gate,
                )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pausingGate =
                object : SyncConsentGate {
                    override suspend fun <T> withConsent(block: suspend (HermesConfig) -> T): T? {
                        entered.countDown()
                        check(release.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            "pausing gate was never released"
                        }
                        return gatekeeper.withConsent(block)
                    }
                }
            val racingCoordinator = RideSyncCoordinator(dataSource, enqueuer, scheduler, pausingGate)

            val job = async(Dispatchers.IO) { racingCoordinator.onRideFinished(RIDE_ID) }
            assertTrue(
                "coordinator never reached the consent gate",
                entered.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            // Simulated disconnect wins the shared gate and revokes everything
            // while the coordinator is still waiting to enter it.
            gate.withLock {
                gate.revoke()
                secretStorage.clear()
                configStore.config = null
            }

            release.countDown()
            val outcome = job.await()

            assertEquals(RideSyncOutcome.Disabled, outcome)
            assertTrue(enqueuer.enqueued.isEmpty())
            assertTrue(scheduler.configs.isEmpty())
        }

    @Test
    fun given_blankRideId_when_onRideFinished_thenFailed() =
        runTest {
            val outcome = coordinator.onRideFinished("   ")

            assertEquals(RideSyncOutcome.Failed, outcome)
            assertEquals(0, dataSource.loadCalls)
        }

    @Test
    fun given_missingRideData_when_onRideFinished_thenNoData() =
        runTest {
            val missingDataCoordinator =
                coordinator(dataSource = FakeRideSyncDataSource(null))

            val outcome = missingDataCoordinator.onRideFinished(RIDE_ID)

            assertEquals(RideSyncOutcome.NoData, outcome)
            assertTrue(enqueuer.enqueued.isEmpty())
            assertTrue(scheduler.configs.isEmpty())
        }

    @Test
    fun given_validRide_when_onRideFinished_thenEnqueuesPayloadAndSchedules() =
        runTest {
            val outcome = coordinator.onRideFinished(RIDE_ID)

            assertEquals(RideSyncOutcome.Enqueued, outcome)
            assertEquals(1, enqueuer.enqueued.size)
            val (rideId, payload) = enqueuer.enqueued.single()
            assertEquals(RIDE_ID, rideId)
            assertTrue(payload.isNotEmpty())
            assertEquals(listOf(CONFIG), scheduler.configs)
        }

    @Test
    fun given_retryCountInConfig_when_onRideFinished_thenEnqueuerReceivesItAsMaxAttempts() =
        runTest {
            configStore.config = CONFIG.copy(retryCount = 7)

            coordinator.onRideFinished(RIDE_ID)

            assertEquals(listOf(7), enqueuer.maxAttemptsRequested)
        }

    @Test
    fun given_zeroRetryCount_when_onRideFinished_thenCoercedToAtLeastOneAttempt() =
        runTest {
            configStore.config = CONFIG.copy(retryCount = 0)

            coordinator.onRideFinished(RIDE_ID)

            assertEquals(listOf(1), enqueuer.maxAttemptsRequested)
        }

    @Test
    fun given_enqueuerFailure_when_onRideFinished_thenFailedAndNotScheduled() =
        runTest {
            val failingEnqueuer = RecordingOutboxEnqueuer(failure = IllegalStateException("outbox down"))
            val failingCoordinator = coordinator(enqueuer = failingEnqueuer)

            val outcome = failingCoordinator.onRideFinished(RIDE_ID)

            assertEquals(RideSyncOutcome.Failed, outcome)
            assertTrue(scheduler.configs.isEmpty())
        }

    @Test
    fun given_schedulerFailure_when_onRideFinished_thenFailedButRowStaysEnqueued() =
        runTest {
            val failingScheduler = RecordingSyncWorkScheduler(failure = IllegalStateException("no workmanager"))
            val failingCoordinator = coordinator(scheduler = failingScheduler)

            val outcome = failingCoordinator.onRideFinished(RIDE_ID)

            assertEquals(RideSyncOutcome.Failed, outcome)
            // The payload is already durable; the next worker pass delivers it.
            assertEquals(1, enqueuer.enqueued.size)
        }

    @Test
    fun given_validRide_when_onRideFinished_thenEnqueuedPayloadContainsRideId() =
        runTest {
            coordinator.onRideFinished(RIDE_ID)

            val json = String(enqueuer.enqueued.single().second, Charsets.UTF_8)
            assertTrue(json.contains("\"rideId\":\"$RIDE_ID\""))
            assertFalse(json.isBlank())
        }

    private fun coordinator(
        dataSource: RideSyncDataSource = this.dataSource,
        enqueuer: OutboxEnqueuer = this.enqueuer,
        scheduler: SyncWorkScheduler = this.scheduler,
        configStore: SyncConfigStore = this.configStore,
        secretStorage: SecretStorage = this.secretStorage,
    ): RideSyncCoordinator =
        RideSyncCoordinator(
            dataSource = dataSource,
            enqueuer = enqueuer,
            scheduler = scheduler,
            gatekeeper =
                SyncGatekeeper(
                    configStore = configStore,
                    secretManager = HmacSecretManager(IdentitySecretSealer, secretStorage),
                    gate = SyncMutationGate(),
                ),
        )

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
        val maxAttemptsRequested: MutableList<Int> = mutableListOf()

        override suspend fun enqueue(
            rideId: String,
            payload: ByteArray,
            maxAttempts: Int,
        ) {
            failure?.let { throw it }
            maxAttemptsRequested += maxAttempts
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

        override fun cancel() = Unit
    }

    private class FakeSyncConfigStore(
        var config: HermesConfig?,
    ) : SyncConfigStore {
        override fun current(): HermesConfig? = config

        override fun save(config: HermesConfig) {
            this.config = config
        }

        override fun clear() {
            config = null
        }
    }

    private class FakeSecretStorage(
        private var sealed: ByteArray?,
    ) : SecretStorage {
        override fun readSealed(): ByteArray? = sealed?.copyOf()

        override fun writeSealed(sealed: ByteArray) {
            this.sealed = sealed.copyOf()
        }

        override fun clear() {
            sealed = null
        }
    }

    private object IdentitySecretSealer : SecretSealer {
        override fun seal(
            plaintext: ByteArray,
            purpose: String,
        ): ByteArray = plaintext.copyOf()

        override fun unseal(
            sealed: ByteArray,
            purpose: String,
        ): UnsealedSecret = UnsealedSecret(sealed.copyOf(), wasLegacy = false)
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
        private const val START_MILLIS = 1_700_000_000_000L
        private const val DURATION_MILLIS = 600_000L
        private const val START_NANOS = 5_000_000_000L
        private const val GATE_TIMEOUT_SECONDS = 5L
        private val CONFIG = HermesConfig(endpoint = "https://hermes.example.test/webhook")
    }
}
