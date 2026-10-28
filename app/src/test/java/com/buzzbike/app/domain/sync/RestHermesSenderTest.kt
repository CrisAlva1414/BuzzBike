package com.buzzbike.app.domain.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [RestHermesSender] configuration validation.
 *
 * Mirrors [WebhookHermesSenderTest]: slice 3.1 performs no real HTTP calls.
 */
class RestHermesSenderTest {
    private val payload = ByteArray(0)

    @Test
    fun given_blankEndpoint_when_sendRide_then_failureWithMessage() =
        runTest {
            val result =
                RestHermesSender(HermesConfig(endpoint = ""))
                    .sendRide(rideId = "ride-1", payload = payload)

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertNotNull(result.errorMessage)
        }

    @Test
    fun given_malformedEndpoint_when_sendRide_then_failureWithMessage() =
        runTest {
            val result =
                RestHermesSender(HermesConfig(endpoint = "not-a-url"))
                    .sendRide(rideId = "ride-1", payload = payload)

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertNotNull(result.errorMessage)
        }

    @Test
    fun given_unsupportedProtocol_when_sendRide_then_failureWithMessage() =
        runTest {
            val result =
                RestHermesSender(HermesConfig(endpoint = "ftp://example.com/rides"))
                    .sendRide(rideId = "ride-1", payload = payload)

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertNotNull(result.errorMessage)
        }

    @Test
    fun given_validEndpoint_when_sendRide_then_success() =
        runTest {
            val result =
                RestHermesSender(HermesConfig(endpoint = "https://api.example.com/rides"))
                    .sendRide(rideId = "ride-1", payload = payload)

            assertTrue(result.success)
            assertEquals(200, result.statusCode)
            assertNull(result.errorMessage)
        }
}
