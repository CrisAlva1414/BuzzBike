package com.buzzbike.app.domain.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [WebhookHermesSender] configuration validation.
 *
 * Slice 3.1 is network-free by design, so every case only exercises the
 * endpoint validation path.
 */
class WebhookHermesSenderTest {
    private val payload = ByteArray(0)

    @Test
    fun given_blankEndpoint_when_sendRide_then_failureWithMessage() =
        runTest {
            val result =
                WebhookHermesSender(HermesConfig(endpoint = "   "))
                    .sendRide(rideId = "ride-1", payload = payload)

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertNotNull(result.errorMessage)
        }

    @Test
    fun given_malformedEndpoint_when_sendRide_then_failureWithMessage() =
        runTest {
            val result =
                WebhookHermesSender(HermesConfig(endpoint = "not-a-url"))
                    .sendRide(rideId = "ride-1", payload = payload)

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertNotNull(result.errorMessage)
        }

    @Test
    fun given_unsupportedProtocol_when_sendRide_then_failureWithMessage() =
        runTest {
            val result =
                WebhookHermesSender(HermesConfig(endpoint = "ftp://example.com/hook"))
                    .sendRide(rideId = "ride-1", payload = payload)

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertNotNull(result.errorMessage)
        }

    @Test
    fun given_validEndpoint_when_sendRide_then_success() =
        runTest {
            val result =
                WebhookHermesSender(HermesConfig(endpoint = "https://example.com/hermes"))
                    .sendRide(rideId = "ride-1", payload = payload)

            assertTrue(result.success)
            assertEquals(200, result.statusCode)
            assertNull(result.errorMessage)
        }
}
