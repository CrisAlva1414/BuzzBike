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
 * must be lossless, and a missing/blank endpoint must produce `null` so the
 * worker fails instead of syncing to an unknown destination.
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
    fun given_configWithoutHealthCheckUrl_when_encodeThenDecode_then_roundTrip() {
        val config = HermesConfig(endpoint = "https://hermes.example/sync")

        val decoded = HermesConfigCodec.decode(HermesConfigCodec.encode(config))

        assertEquals(config, decoded)
        assertNull(decoded?.healthCheckUrl)
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
                    HermesConfigCodec.KEY_SENDER_TYPE to "SMOKE_SIGNAL",
                ),
            )

        assertEquals(SenderType.WEBHOOK, decoded?.senderType)
    }

    @Test
    fun given_onlyEndpoint_when_decode_then_usesDefaults() {
        val decoded = HermesConfigCodec.decode(mapOf(HermesConfigCodec.KEY_ENDPOINT to ENDPOINT))

        assertEquals(
            HermesConfig(endpoint = ENDPOINT),
            decoded,
        )
    }

    @Test
    fun given_unparseableNumbers_when_decode_then_usesDefaults() {
        val decoded =
            HermesConfigCodec.decode(
                mapOf(
                    HermesConfigCodec.KEY_ENDPOINT to ENDPOINT,
                    HermesConfigCodec.KEY_TIMEOUT_SECONDS to "not-a-number",
                    HermesConfigCodec.KEY_RETRY_COUNT to "-3",
                ),
            )

        val defaults = HermesConfig(endpoint = ENDPOINT)
        assertEquals(defaults.timeoutSeconds, decoded?.timeoutSeconds)
        assertEquals(defaults.retryCount, decoded?.retryCount)
    }

    private companion object {
        const val ENDPOINT = "https://hermes.example/sync"
    }
}
