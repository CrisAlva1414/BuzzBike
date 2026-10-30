package com.buzzbike.app.domain.ride.session

import com.buzzbike.app.domain.calories.CalorieModel
import com.buzzbike.app.domain.calories.CalorieResult
import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.elevation.ElevationDescriptors
import com.buzzbike.app.domain.elevation.ElevationSample
import com.buzzbike.app.domain.elevation.ElevationSource
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.engine.RideEngine
import com.buzzbike.app.domain.ride.engine.RideEngineResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [RideSessionManager].
 *
 * [RideEngine], [TrackingManager] and [RideSessionWriter] are faked, so no
 * Android runtime, Room or real sensor is required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
// The lifecycle scenarios share one fake rig; splitting them would duplicate
// hundreds of lines of test doubles for no readability gain.
@Suppress("LargeClass")
class RideSessionManagerTest {
    @Test
    fun given_noPermission_when_startRide_thenReadyWithoutCreatingARide() =
        runTest {
            val writer = FakeRideSessionWriter()
            val tracking = FakeTrackingManager()
            val manager = manager(writer = writer, tracking = tracking, permissionChecker = DeniedPermissionChecker)

            val rideId = manager.startRide()

            assertNull(rideId)
            assertEquals(0, writer.startCalls)
            assertNull(tracking.startedRideId)
            val state = manager.state.first()
            assertTrue(state is RideSessionState.Ready)
            assertEquals(GpsStatus.NO_PERMISSION, (state as RideSessionState.Ready).gps)
            assertFalse(state.hasBarometer)
        }

    @Test
    fun given_permission_when_startRide_thenRecordingAndTrackingStarted() =
        runTest {
            val writer = FakeRideSessionWriter()
            val tracking = FakeTrackingManager()
            val manager = manager(writer = writer, tracking = tracking)

            val rideId = manager.startRide()

            assertEquals(RIDE_ID, rideId)
            assertEquals(RIDE_ID, tracking.startedRideId)
            assertEquals(RIDE_ID, tracking.elevationRideId)
            val state = manager.state.first()
            assertTrue(state is RideSessionState.Recording)
            assertEquals(RIDE_ID, (state as RideSessionState.Recording).rideId)
            assertFalse(state.autoPaused)
        }

    @Test
    fun given_recordingRide_when_pause_thenMetricsAndPointsAreKept() =
        runTest {
            val tracking = FakeTrackingManager()
            val engine = FakeRideEngine { processResult(distanceM = 100.0, movingSeconds = 20L) }
            val manager = manager(tracking = tracking, engine = engine)
            manager.startRide()
            runCurrent()
            tracking.points.value = listOf(point(0), point(10))
            runCurrent()
            val before = (manager.state.first() as RideSessionState.Recording).live

            manager.pauseRide()

            val paused = manager.state.first()
            assertTrue(paused is RideSessionState.Paused)
            assertEquals(100.0, (paused as RideSessionState.Paused).live.distanceM, TOLERANCE)
            assertEquals(before.tailPoints, paused.live.tailPoints)
            assertEquals(2, paused.live.tailPoints.size)
            assertTrue(tracking.pauseCalled)
            assertTrue(tracking.stopElevationCalled)
        }

    @Test
    fun given_pausedRide_when_resume_thenRecordingAgain() =
        runTest {
            val tracking = FakeTrackingManager()
            val manager = manager(tracking = tracking)
            manager.startRide()
            runCurrent()
            manager.pauseRide()

            manager.resumeRide()

            val resumed = manager.state.first()
            assertTrue(resumed is RideSessionState.Recording)
            assertEquals(RIDE_ID, (resumed as RideSessionState.Recording).rideId)
            assertEquals(RIDE_ID, tracking.resumedRideId)
            assertEquals(RIDE_ID, tracking.elevationRideId)
        }

    @Test
    fun given_recordingRide_when_stop_thenFinishedWithPersistedBand() =
        runTest {
            val writer = FakeRideSessionWriter()
            val tracking = FakeTrackingManager()
            val engine =
                FakeRideEngine {
                    processResult(
                        distanceM = 800.0,
                        movingSeconds = 60L,
                        avgSpeedMps = 13.3,
                        caloriesKcal = 99.0,
                    )
                }
            val manager = manager(writer = writer, tracking = tracking, engine = engine)
            manager.startRide()
            runCurrent()
            tracking.points.value = listOf(point(0), point(10))
            runCurrent()
            tracking.elevation.value = elevation(gainM = 30.0, lossM = 20.0)
            runCurrent()

            manager.stopRide()

            assertTrue(tracking.stopTrackingCalled)
            assertTrue(tracking.stopElevationCalled)
            assertEquals(RIDE_ID, writer.stoppedRideId)
            val saved = writer.saved.single()
            assertEquals(800.0, saved.descriptors.distanceM, TOLERANCE)
            assertEquals(60L, saved.descriptors.movingSeconds)
            assertEquals(13.3, saved.descriptors.avgSpeedMps, TOLERANCE)
            assertEquals(13.3, saved.descriptors.maxSpeedMps, TOLERANCE)
            assertEquals(30.0, saved.elevationGainM, TOLERANCE)
            assertEquals(20.0, saved.elevationLossM, TOLERANCE)
            assertEquals(99.0, saved.caloriesKcal ?: Double.NaN, TOLERANCE)
            assertEquals(69.3, saved.caloriesLowKcal ?: Double.NaN, TOLERANCE)
            assertEquals(128.7, saved.caloriesHighKcal ?: Double.NaN, TOLERANCE)
            assertEquals("COMPENDIUM_VELOCITY", saved.calorieModel)

            val finished = manager.state.first()
            assertTrue(finished is RideSessionState.Finished)
            val summary = (finished as RideSessionState.Finished).summary
            assertEquals(99.0, summary.calories?.estimateKcal ?: Double.NaN, TOLERANCE)
            assertEquals(69.3, summary.calories?.lowKcal ?: Double.NaN, TOLERANCE)
            assertEquals(128.7, summary.calories?.highKcal ?: Double.NaN, TOLERANCE)
            assertEquals(false, summary.hasProfile)
        }

    @Test
    fun given_recordingRide_when_discard_thenRideIsDeletedAndReady() =
        runTest {
            val writer = FakeRideSessionWriter()
            val manager = manager(writer = writer)
            manager.startRide()
            runCurrent()

            manager.discardRide()

            assertEquals(RIDE_ID, writer.deletedRideId)
            val state = manager.state.first()
            assertTrue(state is RideSessionState.Ready)
            assertEquals(GpsStatus.SEARCHING, (state as RideSessionState.Ready).gps)
        }

    @Test
    fun given_persistedActiveRide_when_restore_thenRecoverable() =
        runTest {
            val writer = FakeRideSessionWriter(activeRideId = RIDE_ID)
            val manager = manager(writer = writer)

            manager.restore()

            val state = manager.state.first()
            assertTrue(state is RideSessionState.Recoverable)
            assertEquals(RIDE_ID, (state as RideSessionState.Recoverable).rideId)
        }

    @Test
    fun given_noPersistedRide_when_restore_thenReadySearching() =
        runTest {
            val manager = manager(permissionChecker = GrantedPermissionChecker)

            manager.restore()

            val state = manager.state.first()
            assertTrue(state is RideSessionState.Ready)
            assertEquals(GpsStatus.SEARCHING, (state as RideSessionState.Ready).gps)
        }

    @Test
    fun given_recordingRide_when_restore_thenKeepsRecording() =
        runTest {
            val writer = FakeRideSessionWriter()
            val manager = manager(writer = writer)
            manager.startRide()
            runCurrent()
            // A stale persisted id must not replace the live session.
            writer.activeRideId = STALE_RIDE_ID

            manager.restore()

            val state = manager.state.first()
            assertTrue(state is RideSessionState.Recording)
            assertEquals(RIDE_ID, (state as RideSessionState.Recording).rideId)
        }

    @Test
    fun given_pausedRide_when_restore_thenKeepsPaused() =
        runTest {
            val writer = FakeRideSessionWriter()
            val manager = manager(writer = writer)
            manager.startRide()
            runCurrent()
            manager.pauseRide()
            writer.activeRideId = STALE_RIDE_ID

            manager.restore()

            val state = manager.state.first()
            assertTrue(state is RideSessionState.Paused)
            assertEquals(RIDE_ID, (state as RideSessionState.Paused).rideId)
        }

    @Test
    fun given_finishedRide_when_restore_thenKeepsFinished() =
        runTest {
            val writer = FakeRideSessionWriter()
            val manager = manager(writer = writer)
            manager.startRide()
            runCurrent()
            manager.stopRide()
            writer.activeRideId = STALE_RIDE_ID

            manager.restore()

            val state = manager.state.first()
            assertTrue(state is RideSessionState.Finished)
            assertEquals(RIDE_ID, (state as RideSessionState.Finished).rideId)
        }

    @Test
    fun given_recoverableRideWithPoints_when_salvage_thenFinishedWithBandAndRowStopped() =
        runTest {
            val writer = FakeRideSessionWriter(activeRideId = RIDE_ID)
            val engine =
                FakeRideEngine {
                    processResult(
                        distanceM = 500.0,
                        movingSeconds = 40L,
                        avgSpeedMps = 12.5,
                        caloriesKcal = 80.0,
                    )
                }
            val manager = manager(writer = writer, engine = engine)
            manager.restore()
            writer.points = listOf(point(0), point(10))

            val salvaged = manager.salvageRecoverableRide()

            assertEquals(RIDE_ID, salvaged)
            assertEquals(listOf(listOf(point(0), point(10))), engine.received)
            assertEquals(1, writer.stopCalls)
            assertEquals(RIDE_ID, writer.stoppedRideId)
            val saved = writer.saved.single()
            assertEquals(500.0, saved.descriptors.distanceM, TOLERANCE)
            assertEquals(80.0, saved.caloriesKcal ?: Double.NaN, TOLERANCE)
            assertEquals(56.0, saved.caloriesLowKcal ?: Double.NaN, TOLERANCE)
            assertEquals(104.0, saved.caloriesHighKcal ?: Double.NaN, TOLERANCE)
            assertEquals("COMPENDIUM_VELOCITY", saved.calorieModel)
            val finished = manager.state.first()
            assertTrue(finished is RideSessionState.Finished)
            assertEquals(RIDE_ID, (finished as RideSessionState.Finished).rideId)
            assertEquals(80.0, finished.summary.calories?.estimateKcal ?: Double.NaN, TOLERANCE)
        }

    @Test
    fun given_recoverableRideWithoutPoints_when_salvage_thenDeletesRowAndReady() =
        runTest {
            val writer = FakeRideSessionWriter(activeRideId = RIDE_ID)
            val manager = manager(writer = writer)
            manager.restore()

            val salvaged = manager.salvageRecoverableRide()

            assertNull(salvaged)
            assertEquals(RIDE_ID, writer.deletedRideId)
            assertEquals(0, writer.stopCalls)
            assertTrue(writer.saved.isEmpty())
            val state = manager.state.first()
            assertTrue(state is RideSessionState.Ready)
        }

    @Test
    fun given_readyRide_when_salvage_thenNoOp() =
        runTest {
            val writer = FakeRideSessionWriter()
            val manager = manager(writer = writer)
            manager.restore()

            val salvaged = manager.salvageRecoverableRide()

            assertNull(salvaged)
            assertNull(writer.deletedRideId)
            assertNull(writer.stoppedRideId)
            assertTrue(writer.saved.isEmpty())
            assertTrue(manager.state.first() is RideSessionState.Ready)
        }

    @Test
    fun given_recordingRide_when_salvage_thenNoOp() =
        runTest {
            val writer = FakeRideSessionWriter()
            val manager = manager(writer = writer)
            manager.startRide()
            runCurrent()

            val salvaged = manager.salvageRecoverableRide()

            assertNull(salvaged)
            assertNull(writer.deletedRideId)
            assertNull(writer.stoppedRideId)
            assertTrue(writer.saved.isEmpty())
            assertTrue(manager.state.first() is RideSessionState.Recording)
        }

    @Test
    fun given_movingTrace_when_pointsEmitted_thenAutoPausedIsFalse() =
        runTest {
            val tracking = FakeTrackingManager()
            val manager = manager(tracking = tracking)
            manager.startRide()
            runCurrent()

            tracking.points.value = List(20) { point(seconds = it.toLong(), speedMps = 5.0) }
            runCurrent()

            val state = manager.state.first()
            assertTrue(state is RideSessionState.Recording)
            assertFalse((state as RideSessionState.Recording).autoPaused)
        }

    @Test
    fun given_confirmedStop_when_pointsEmitted_thenAutoPausedIsTrue() =
        runTest {
            val tracking = FakeTrackingManager()
            val manager = manager(tracking = tracking)
            manager.startRide()
            runCurrent()

            val moving = List(20) { point(seconds = it.toLong(), speedMps = 5.0) }
            val stopped =
                List(STOP_SECONDS) { offset ->
                    point(seconds = 20L + offset, speedMps = 0.0)
                }
            tracking.points.value = moving + stopped
            runCurrent()

            val state = manager.state.first()
            assertTrue(state is RideSessionState.Recording)
            assertTrue((state as RideSessionState.Recording).autoPaused)
        }

    @Test
    fun given_manyPoints_when_emitted_thenTailPointsAreCapped() =
        runTest {
            val tracking = FakeTrackingManager()
            val manager = manager(tracking = tracking)
            manager.startRide()
            runCurrent()

            tracking.points.value =
                List(TAIL_SOURCE_POINTS) { index ->
                    point(seconds = index.toLong(), speedMps = 5.0)
                }
            runCurrent()

            val state = manager.state.first() as RideSessionState.Recording
            assertEquals(TAIL_POINTS_LIMIT, state.live.tailPoints.size)
        }

    @Test
    fun given_manyPoints_when_emitted_thenTailPointsAreTheNewestInArrivalOrder() =
        runTest {
            val tracking = FakeTrackingManager()
            val manager = manager(tracking = tracking)
            manager.startRide()
            runCurrent()

            val lats = List(TAIL_SOURCE_POINTS) { index -> BASE_LAT + index * LAT_STEP }
            tracking.points.value =
                lats.mapIndexed { index, lat ->
                    point(seconds = index.toLong(), lat = lat)
                }
            runCurrent()

            val tail = (manager.state.first() as RideSessionState.Recording).live.tailPoints
            assertEquals(TAIL_POINTS_LIMIT, tail.size)
            assertEquals(lats.takeLast(TAIL_POINTS_LIMIT), tail.map { it.lat })
        }

    @Test
    fun given_trackingRejectsStart_when_startRide_thenRowIsDeletedAndNoOrphanRemains() =
        runTest {
            val writer = FakeRideSessionWriter()
            val tracking = FakeTrackingManager().apply { startTrackingResult = false }
            val manager = manager(writer = writer, tracking = tracking)

            val rideId = manager.startRide()

            assertNull(rideId)
            assertEquals(1, writer.startCalls)
            assertEquals(RIDE_ID, writer.deletedRideId)
            assertNull(writer.activeRideId)
            assertTrue(tracking.stopTrackingCalled)
            val state = manager.state.first()
            assertTrue(state is RideSessionState.Ready)
            assertEquals(GpsStatus.NO_PERMISSION, (state as RideSessionState.Ready).gps)
        }

    @Test
    fun given_pausedRide_when_resumedAndMorePointsEmitted_thenAccumulatedTailIsKept() =
        runTest {
            val tracking = FakeTrackingManager()
            val manager = manager(tracking = tracking)
            manager.startRide()
            runCurrent()

            val firstBatch =
                listOf(
                    point(seconds = 0, lat = BASE_LAT),
                    point(seconds = 1, lat = BASE_LAT + LAT_STEP),
                )
            tracking.points.value = firstBatch
            runCurrent()
            manager.pauseRide()

            manager.resumeRide()
            tracking.points.value = firstBatch + point(seconds = 2, lat = BASE_LAT + 2 * LAT_STEP)
            runCurrent()

            val live = (manager.state.first() as RideSessionState.Recording).live
            assertEquals(
                listOf(BASE_LAT, BASE_LAT + LAT_STEP, BASE_LAT + 2 * LAT_STEP),
                live.tailPoints.map { it.lat },
            )
        }

    @Test
    fun given_livePointsAndElevation_when_emitted_thenLiveMetricsReflectSources() =
        runTest {
            val tracking = FakeTrackingManager()
            val engine =
                FakeRideEngine {
                    processResult(
                        distanceM = 1234.5,
                        movingSeconds = 321L,
                        avgSpeedMps = 8.4,
                        caloriesKcal = 250.0,
                    )
                }
            val manager = manager(tracking = tracking, engine = engine)
            manager.startRide()
            runCurrent()

            tracking.points.value =
                listOf(
                    point(seconds = 0, speedMps = 4.0),
                    point(seconds = 2, speedMps = 7.5),
                )
            tracking.elevation.value = elevation(gainM = 75.0)
            runCurrent()

            val live = (manager.state.first() as RideSessionState.Recording).live
            assertEquals(1234.5, live.distanceM, TOLERANCE)
            assertEquals(321L, live.movingSeconds)
            assertEquals(75.0, live.elevationGainM, TOLERANCE)
            // Instantaneous speed of the last usable fix (7.5), not an average.
            assertEquals(7.5, live.speedMps, TOLERANCE)
            assertEquals(250.0, live.calories?.estimateKcal ?: Double.NaN, TOLERANCE)
            assertEquals(175.0, live.calories?.lowKcal ?: Double.NaN, TOLERANCE)
            assertEquals(325.0, live.calories?.highKcal ?: Double.NaN, TOLERANCE)
            assertEquals("COMPENDIUM_VELOCITY", live.calories?.model)
            assertEquals(false, live.hasProfile)
            assertEquals(2, live.tailPoints.size)
        }

    @Test
    fun given_justStartedRideWithoutConfirmedMovement_when_pointsEmitted_thenAutoPausedIsFalse() =
        runTest {
            val tracking = FakeTrackingManager()
            val manager = manager(tracking = tracking)
            manager.startRide()
            runCurrent()

            // The estimator starts STOPPED without a confirmed stop window, so
            // a stationary start must not trip auto-pause (ADR-005 §4).
            tracking.points.value = List(STOP_SECONDS) { point(seconds = it.toLong(), speedMps = 0.0) }
            runCurrent()

            val state = manager.state.first() as RideSessionState.Recording
            assertFalse(state.autoPaused)
        }

    @Test
    fun given_recordingRideWithoutPoints_when_stop_thenFinishesEmptyWithoutCrash() =
        runTest {
            val writer = FakeRideSessionWriter()
            // Real engine: stop with an empty trace must still persist a ride.
            val manager = manager(writer = writer, engine = RideEngine())
            manager.startRide()
            runCurrent()

            manager.stopRide()

            val finished = manager.state.first()
            assertTrue(finished is RideSessionState.Finished)
            val summary = (finished as RideSessionState.Finished).summary
            assertEquals(0.0, summary.descriptors.distanceM, TOLERANCE)
            assertEquals(0L, summary.descriptors.movingSeconds)
            assertNull(summary.calories)
            assertEquals(RIDE_ID, writer.stoppedRideId)
            assertEquals(1, writer.saved.size)
        }

    @Test
    fun given_finishedRide_when_acknowledgeFinished_thenReady() =
        runTest {
            val manager = manager()
            manager.startRide()
            runCurrent()
            manager.stopRide()
            assertTrue(manager.state.first() is RideSessionState.Finished)

            manager.acknowledgeFinished()

            val state = manager.state.first()
            assertTrue(state is RideSessionState.Ready)
            assertEquals(GpsStatus.SEARCHING, (state as RideSessionState.Ready).gps)
        }

    @Test
    fun given_idle_when_startRideTwiceConcurrently_thenOnlyOneRowIsCreated() =
        runTest {
            val writer = FakeRideSessionWriter()
            val tracking = FakeTrackingManager()
            val manager = manager(writer = writer, tracking = tracking)

            val first = async { manager.startRide() }
            val second = async { manager.startRide() }
            val results = listOf(first.await(), second.await())

            assertEquals(1, results.count { it != null })
            assertEquals(1, writer.startCalls)
            val state = manager.state.first()
            assertTrue(state is RideSessionState.Recording)
            assertEquals(RIDE_ID, (state as RideSessionState.Recording).rideId)
        }

    @Test
    fun given_recordingRide_when_startRideAgain_thenReturnsNullWithoutCreatingRow() =
        runTest {
            val writer = FakeRideSessionWriter()
            val manager = manager(writer = writer)
            manager.startRide()
            runCurrent()

            val secondStart = manager.startRide()

            assertNull(secondStart)
            assertEquals(1, writer.startCalls)
            val state = manager.state.first()
            assertTrue(state is RideSessionState.Recording)
            assertEquals(RIDE_ID, (state as RideSessionState.Recording).rideId)
        }

    @Test
    fun given_pausedRide_when_startRideAgain_thenReturnsNullWithoutCreatingRow() =
        runTest {
            val writer = FakeRideSessionWriter()
            val manager = manager(writer = writer)
            manager.startRide()
            runCurrent()
            manager.pauseRide()

            val secondStart = manager.startRide()

            assertNull(secondStart)
            assertEquals(1, writer.startCalls)
            val state = manager.state.first()
            assertTrue(state is RideSessionState.Paused)
            assertEquals(RIDE_ID, (state as RideSessionState.Paused).rideId)
        }

    @Test
    fun given_recoverableRide_when_startRideAgain_thenReturnsNullWithoutCreatingRow() =
        runTest {
            val writer = FakeRideSessionWriter(activeRideId = RIDE_ID)
            val manager = manager(writer = writer)
            manager.restore()

            val secondStart = manager.startRide()

            assertNull(secondStart)
            assertEquals(0, writer.startCalls)
            val state = manager.state.first()
            assertTrue(state is RideSessionState.Recoverable)
            assertEquals(RIDE_ID, (state as RideSessionState.Recoverable).rideId)
        }

    @Test
    fun given_pausedRide_when_stop_thenFinishedWithPersistedDescriptors() =
        runTest {
            val writer = FakeRideSessionWriter()
            val tracking = FakeTrackingManager()
            val manager = manager(writer = writer, tracking = tracking)
            manager.startRide()
            runCurrent()
            tracking.points.value = listOf(point(0), point(10))
            runCurrent()
            manager.pauseRide()

            manager.stopRide()

            assertTrue(tracking.stopTrackingCalled)
            assertTrue(tracking.stopElevationCalled)
            assertEquals(RIDE_ID, writer.stoppedRideId)
            assertEquals(1, writer.saved.size)
            val finished = manager.state.first()
            assertTrue(finished is RideSessionState.Finished)
            assertEquals(RIDE_ID, (finished as RideSessionState.Finished).rideId)
        }

    @Test
    fun given_resumeTrackingRejected_when_resume_thenStaysPausedWithoutElevation() =
        runTest {
            val tracking = FakeTrackingManager()
            val manager = manager(tracking = tracking)
            manager.startRide()
            runCurrent()
            tracking.points.value = listOf(point(0))
            runCurrent()
            manager.pauseRide()
            val paused = manager.state.first() as RideSessionState.Paused
            tracking.resumeTrackingResult = false

            manager.resumeRide()

            val after = manager.state.first()
            assertTrue(after is RideSessionState.Paused)
            assertEquals(paused.rideId, (after as RideSessionState.Paused).rideId)
            assertEquals(paused.live, after.live)
            assertEquals(1, tracking.elevationStartCalls)
        }

    @Test
    fun given_inFlightCollector_when_discard_thenWaitsForCancellationBeforeDeleting() =
        runTest {
            val order = mutableListOf<String>()
            val writer = FakeRideSessionWriter(onDelete = { order += "delete" })
            val tracking = FakeTrackingManager()
            tracking.trackPointsFlow =
                flow {
                    emit(emptyList())
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { delay(COLLECTOR_CLEANUP_DELAY_MS) }
                        order += "collector"
                    }
                }
            val manager = manager(writer = writer, tracking = tracking)
            manager.startRide()
            runCurrent()

            manager.discardRide()

            assertEquals(listOf("collector", "delete"), order)
            assertEquals(RIDE_ID, writer.deletedRideId)
            assertTrue(manager.state.first() is RideSessionState.Ready)
        }

    private fun TestScope.manager(
        writer: FakeRideSessionWriter = FakeRideSessionWriter(),
        tracking: FakeTrackingManager = FakeTrackingManager(),
        engine: RideEngine = FakeRideEngine { processResult() },
        permissionChecker: LocationPermissionChecker = GrantedPermissionChecker,
    ): RideSessionManager =
        RideSessionManager(
            rideEngine = engine,
            trackingManager = tracking,
            rideSessionWriter = writer,
            permissionChecker = permissionChecker,
            scope = backgroundScope,
        )

    private fun point(
        seconds: Long,
        speedMps: Double? = null,
        lat: Double = BASE_LAT,
    ): TrackPoint =
        TrackPoint(
            timestampMonotonicNanos = seconds * NANOS_PER_SECOND,
            lat = lat,
            lon = BASE_LON,
            accuracyH = ACCURACY_M,
            speedMps = speedMps,
        )

    private fun elevation(
        gainM: Double,
        lossM: Double = 0.0,
    ): ElevationDescriptors =
        ElevationDescriptors(
            gainM = gainM,
            lossM = lossM,
            netM = gainM - lossM,
            source = ElevationSource.BAROMETER,
            confidence = CONFIDENCE,
            hasBarometer = true,
            anchorSource = ElevationSource.GPS_MSL,
        )

    private fun processResult(
        distanceM: Double = 0.0,
        movingSeconds: Long = 0L,
        avgSpeedMps: Double = 0.0,
        caloriesKcal: Double? = null,
    ): RideEngineResult =
        RideEngineResult(
            rideDescriptors =
                RideDescriptors(
                    distanceM = distanceM,
                    elapsedSeconds = movingSeconds,
                    movingSeconds = movingSeconds,
                    stoppedSeconds = 0L,
                    avgMovingSpeedMps = avgSpeedMps,
                    avgSpeedMps = avgSpeedMps,
                    maxSpeedMps = avgSpeedMps,
                    stopCount = 0,
                    stopDurationsSeconds = emptyList(),
                    acceptedPoints = 0,
                    rejectedPoints = 0,
                    gapCount = 0,
                    distanceIsLowerBound = false,
                    modelVersion = MODEL_VERSION,
                ),
            elevationDescriptors = elevation(gainM = 0.0),
            calorieResult = caloriesKcal?.let { calorieResult(it) },
            acceptedPoints = 0,
            rejectedPoints = 0,
        )

    private fun calorieResult(kcal: Double): CalorieResult =
        CalorieResult(
            totalKcal = kcal,
            activeKcal = kcal,
            metMinutes = 0.0,
            model = CalorieModel.COMPENDIUM_VELOCITY,
            uncertaintyMinPercent = UNCERTAINTY_MIN_PERCENT,
            uncertaintyMaxPercent = UNCERTAINTY_MAX_PERCENT,
            hasProfile = false,
            missingInputs = emptyList(),
        )

    private data class SavedDescriptors(
        val rideId: String,
        val descriptors: RideDescriptors,
        val elevationGainM: Double,
        val elevationLossM: Double,
        val caloriesKcal: Double?,
        val caloriesLowKcal: Double?,
        val caloriesHighKcal: Double?,
        val calorieModel: String?,
    )

    private class FakeRideSessionWriter(
        var activeRideId: String? = null,
        private val onDelete: (() -> Unit)? = null,
        var points: List<TrackPoint> = emptyList(),
    ) : RideSessionWriter {
        var startCalls: Int = 0
            private set

        var stoppedRideId: String? = null
            private set

        var stopCalls: Int = 0
            private set

        var deletedRideId: String? = null
            private set

        val saved = mutableListOf<SavedDescriptors>()

        override suspend fun startRide(): String {
            startCalls += 1
            activeRideId = RIDE_ID
            return RIDE_ID
        }

        override suspend fun findActiveRideId(): String? = activeRideId

        override suspend fun loadTrackPoints(rideId: String): List<TrackPoint> = points

        override suspend fun stopRide(rideId: String) {
            stopCalls += 1
            stoppedRideId = rideId
            activeRideId = null
        }

        override suspend fun deleteRide(rideId: String) {
            onDelete?.invoke()
            deletedRideId = rideId
            activeRideId = null
        }

        override suspend fun saveRideDescriptors(
            rideId: String,
            descriptors: RideDescriptors,
            elevationGainM: Double,
            elevationLossM: Double,
            caloriesKcal: Double?,
            caloriesLowKcal: Double?,
            caloriesHighKcal: Double?,
            calorieModel: String?,
        ) {
            saved +=
                SavedDescriptors(
                    rideId,
                    descriptors,
                    elevationGainM,
                    elevationLossM,
                    caloriesKcal,
                    caloriesLowKcal,
                    caloriesHighKcal,
                    calorieModel,
                )
        }
    }

    private class FakeRideEngine(
        private val onProcess: (List<TrackPoint>) -> RideEngineResult,
    ) : RideEngine() {
        val received = mutableListOf<List<TrackPoint>>()

        override fun processRide(
            rawPoints: List<TrackPoint>,
            elevationSamples: List<ElevationSample>,
            profile: UserProfile,
        ): RideEngineResult {
            received += rawPoints
            return onProcess(rawPoints)
        }
    }

    private class FakeTrackingManager :
        TrackingManager(
            locationSensor = EmptyLocationSensor,
            trackPointWriter = NoOpTrackPointWriter,
            permissionChecker = GrantedPermissionChecker,
        ) {
        val points = MutableStateFlow<List<TrackPoint>>(emptyList())
        val elevation = MutableStateFlow(emptyElevation())

        var startedRideId: String? = null
            private set

        var elevationRideId: String? = null
            private set

        var stopTrackingCalled: Boolean = false
            private set

        var pauseCalled: Boolean = false
            private set

        var resumedRideId: String? = null
            private set

        var stopElevationCalled: Boolean = false
            private set

        var startTrackingResult: Boolean = true

        var resumeTrackingResult: Boolean = true

        var trackPointsFlow: Flow<List<TrackPoint>>? = null

        var elevationStartCalls: Int = 0
            private set

        override val trackPoints: Flow<List<TrackPoint>>
            get() = trackPointsFlow ?: points

        override val elevationDescriptors: Flow<ElevationDescriptors>
            get() = elevation

        override fun startTracking(rideId: String): Boolean {
            startedRideId = rideId
            return startTrackingResult
        }

        override fun stopTracking() {
            stopTrackingCalled = true
        }

        override fun pauseTracking() {
            pauseCalled = true
        }

        override fun resumeTracking(rideId: String): Boolean {
            resumedRideId = rideId
            return resumeTrackingResult
        }

        override fun startElevationTracking(rideId: String): Boolean {
            elevationStartCalls += 1
            elevationRideId = rideId
            return true
        }

        override fun stopElevationTracking() {
            stopElevationCalled = true
        }

        private companion object {
            fun emptyElevation(): ElevationDescriptors =
                ElevationDescriptors(
                    gainM = 0.0,
                    lossM = 0.0,
                    netM = 0.0,
                    source = ElevationSource.NONE,
                    confidence = 0.0,
                    hasBarometer = false,
                    anchorSource = ElevationSource.NONE,
                )
        }
    }

    private object EmptyLocationSensor : LocationSensorPort {
        override fun locationUpdates(): Flow<LocationReading> = emptyFlow()
    }

    private object NoOpTrackPointWriter : TrackPointWriter {
        override suspend fun saveTrackPoint(
            rideId: String,
            point: TrackPoint,
        ) = Unit
    }

    private object GrantedPermissionChecker : LocationPermissionChecker {
        override fun hasFineLocationPermission(): Boolean = true
    }

    private object DeniedPermissionChecker : LocationPermissionChecker {
        override fun hasFineLocationPermission(): Boolean = false
    }

    private companion object {
        private const val RIDE_ID = "ride-1"
        private const val STALE_RIDE_ID = "ride-stale"
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BASE_LAT = -33.45
        private const val BASE_LON = -70.66
        private const val ACCURACY_M = 5.0
        private const val TOLERANCE = 0.001
        private const val CONFIDENCE = 0.8
        private const val MODEL_VERSION = "test"
        private const val UNCERTAINTY_MIN_PERCENT = 20
        private const val UNCERTAINTY_MAX_PERCENT = 30
        private const val STOP_SECONDS = 15
        private const val TAIL_SOURCE_POINTS = 250
        private const val TAIL_POINTS_LIMIT = 200
        private const val LAT_STEP = 0.00001
        private const val COLLECTOR_CLEANUP_DELAY_MS = 1_000L
    }
}
