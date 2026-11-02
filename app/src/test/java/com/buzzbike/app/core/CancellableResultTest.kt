package com.buzzbike.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CancellableResultTest {
    @Test
    fun given_nonCancellationFailure_when_run_thenFailureIsCaptured() {
        val result = runCatchingCancellable { error("boom") }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun given_cancelledCoroutine_when_blockSuspends_thenCancellationPropagates() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var captured: Result<Unit>? = null
            val job =
                launch {
                    captured =
                        runCatchingCancellable {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                }
            runCurrent()
            started.await()

            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            // A swallowing helper would have assigned Result.failure(CancellationException)
            // before the cancelled coroutine completed.
            assertNull(captured)
        }

    @Test
    fun given_cancellationExceptionThrown_when_run_thenItIsRethrown() {
        val failure = CancellationException("cancelled")

        val thrown =
            runCatching {
                runCatchingCancellable { throw failure }
            }.exceptionOrNull()

        assertTrue(thrown === failure)
    }
}
