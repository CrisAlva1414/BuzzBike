package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.SenderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [HermesConfigCodec].
 *
 * The codec is the only bridge between a [HermesConfig] and WorkManager's
 * primitive input data, so these tests pin both directions: a full round-trip
 * must be lossless, and a missing/blank endpoint **or health-check URL** must
 * produce `null` so the worker fails instead of syncing (or probing) an
 * unknown destination.
 */
class HermesConfigCodecTest {
    @Test
    fun given_fullConfig_when_encodeThenDecode_then_roundTrip() {
        val config =
            HermesConfig(
                endpoint = "https://hermes.example/sync",
                method = "PUT",
                timeoutSeconds = 45,
                retryCount = 7,
                senderType = SenderType.REST,
                healthCheckUrl = "https://hermes.example/health",
            )

        val decoded = HermesConfigCodec.decode(HermesConfigCodec.encode(config))

        assertEquals(config, decoded)
    }

    @Test
    fun given_configWithoutHealthCheckUrl_when_encodeThenDecode_then_null() {
        val config = HermesConfig(endpoint = "https://hermes.example/sync")

        val decoded = HermesConfigCodec.decode(HermesConfigCodec.encode(config))

        assertNull(decoded)
    }

    @Test
    fun given_mapWithoutHealthCheckUrl_when_decode_then_null() {
        assertNull(HermesConfigCodec.decode(mapOf(HermesConfigCodec.KEY_ENDPOINT to ENDPOINT)))
    }

    @Test
    fun given_blankHealthCheckUrl_when_decode_then_null() {
        assertNull(
            HermesConfigCodec.decode(
                mapOf(
                    HermesConfigCodec.KEY_ENDPOINT to ENDPOINT,
                    HermesConfigCodec.KEY_HEALTH_CHECK_URL to "   ",
                ),
            ),
        )
    }

    @Test
    fun given_mapWithoutEndpoint_when_decode_then_null() {
        assertNull(HermesConfigCodec.decode(mapOf(HermesConfigCodec.KEY_METHOD to "POST")))
    }

    @Test
    fun given_blankEndpoint_when_decode_then_null() {
        assertNull(HermesConfigCodec.decode(mapOf(HermesConfigCodec.KEY_ENDPOINT to "   ")))
    }

    @Test
    fun given_unknownSenderType_when_decode_then_webhook() {
        val decoded =
            HermesConfigCodec.decode(
                mapOf(
                    HermesConfigCodec.KEY_ENDPOINT to ENDPOINT,
                    HermesConfigCodec.KEY_HEALTH_CHECK_URL to HEALTH_URL,
                    HermesConfigCodec.KEY_SENDER_TYPE to "SMOKE_SIGNAL",
                ),
            )

        assertEquals(SenderType.WEBHOOK, decoded?.senderType)
    }

    @Test
    fun given_endpointAndHealthUrl_when_decode_then_usesDefaults() {
        val decoded =
            HermesConfigCodec.decode(
                mapOf(
                    HermesConfigCodec.KEY_ENDPOINT to ENDPOINT,
                    HermesConfigCodec.KEY_HEALTH_CHECK_URL to HEALTH_URL,
                ),
            )

        assertEquals(
            HermesConfig(endpoint = ENDPOINT, healthCheckUrl = HEALTH_URL),
            decoded,
        )
    }

    @Test
    fun given_unparseableNumbers_when_decode_then_usesDefaults() {
        val decoded =
            HermesConfigCodec.decode(
                mapOf(
                    HermesConfigCodec.KEY_ENDPOINT to ENDPOINT,
                    HermesConfigCodec.KEY_HEALTH_CHECK_URL to HEALTH_URL,
                    HermesConfigCodec.KEY_TIMEOUT_SECONDS to "not-a-number",
                    HermesConfigCodec.KEY_RETRY_COUNT to "-3",
                ),
            )

        val defaults = HermesConfig(endpoint = ENDPOINT, healthCheckUrl = HEALTH_URL)
        assertEquals(defaults.timeoutSeconds, decoded?.timeoutSeconds)
        assertEquals(defaults.retryCount, decoded?.retryCount)
    }

    private companion object {
        const val ENDPOINT = "https://hermes.example/sync"
        const val HEALTH_URL = "https://hermes.example/health"
    }
}
