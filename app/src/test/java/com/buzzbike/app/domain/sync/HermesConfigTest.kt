package com.buzzbike.app.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [HermesConfig] URL rules.
 *
 * They pin two security-sensitive decisions: there is **no** fallback from the
 * dedicated health-check URL to the send endpoint, and both URLs must be
 * HTTPS with a host and without userinfo, query or fragment. Tailscale CGNAT addresses and
 * MagicDNS names are explicitly accepted because they are the intended
 * deployment topology (ADR-003), not because validation is lenient.
 */
class HermesConfigTest {
    @Test
    fun given_missingHealthCheckUrl_when_effectiveHealthCheckUrl_then_nullWithoutEndpointFallback() {
        val config = HermesConfig(endpoint = HTTPS_ENDPOINT)

        assertNull(config.effectiveHealthCheckUrl())
    }

    @Test
    fun given_blankHealthCheckUrl_when_effectiveHealthCheckUrl_then_null() {
        val config = HermesConfig(endpoint = HTTPS_ENDPOINT, healthCheckUrl = "   ")

        assertNull(config.effectiveHealthCheckUrl())
    }

    @Test
    fun given_healthCheckUrlWithSpaces_when_effectiveHealthCheckUrl_then_trimmed() {
        val config = HermesConfig(endpoint = HTTPS_ENDPOINT, healthCheckUrl = "  $HTTPS_HEALTH  ")

        assertEquals(HTTPS_HEALTH, config.effectiveHealthCheckUrl())
    }

    @Test
    fun given_httpEndpoint_when_endpointValidationError_then_error() {
        assertNotNull(endpointValidationError("http://hermes.example/sync"))
    }

    @Test
    fun given_httpsEndpoint_when_endpointValidationError_then_null() {
        assertNull(endpointValidationError(HTTPS_ENDPOINT))
    }

    @Test
    fun given_userInfoInEndpoint_when_endpointValidationError_then_error() {
        assertNotNull(endpointValidationError("https://user:pass@hermes.example/sync"))
    }

    @Test
    fun given_queryInEndpoint_when_endpointValidationError_then_error() {
        assertNotNull(endpointValidationError("https://hermes.example/sync?token=secret"))
    }

    @Test
    fun given_fragmentInEndpoint_when_endpointValidationError_then_error() {
        assertNotNull(endpointValidationError("https://hermes.example/sync#frag"))
    }

    @Test
    fun given_cleanPathEndpoint_when_endpointValidationError_then_null() {
        assertNull(endpointValidationError("https://hermes.example/sync"))
    }

    @Test
    fun given_queryInHealthCheckUrl_when_healthCheckValidationError_then_error() {
        val config =
            HermesConfig(
                endpoint = HTTPS_ENDPOINT,
                healthCheckUrl = "https://hermes.example/health?token=secret",
            )

        assertNotNull(config.healthCheckValidationError())
    }

    @Test
    fun given_fragmentInHealthCheckUrl_when_healthCheckValidationError_then_error() {
        val config =
            HermesConfig(
                endpoint = HTTPS_ENDPOINT,
                healthCheckUrl = "https://hermes.example/health#frag",
            )

        assertNotNull(config.healthCheckValidationError())
    }

    @Test
    fun given_blankEndpoint_when_endpointValidationError_then_error() {
        assertNotNull(endpointValidationError("   "))
    }

    @Test
    fun given_malformedEndpoint_when_endpointValidationError_then_error() {
        assertNotNull(endpointValidationError("not-a-url"))
    }

    @Test
    fun given_tailscaleCgnatEndpoint_when_endpointValidationError_then_null() {
        assertNull(endpointValidationError("https://100.64.0.10:8644/webhooks/ride"))
    }

    @Test
    fun given_tailscaleMagicDnsEndpoint_when_endpointValidationError_then_null() {
        assertNull(endpointValidationError("https://buzzbike-nas.tailnet-example.ts.net:8644/webhooks/ride"))
    }

    @Test
    fun given_missingHealthCheckUrl_when_healthCheckValidationError_then_error() {
        assertNotNull(HermesConfig(endpoint = HTTPS_ENDPOINT).healthCheckValidationError())
    }

    @Test
    fun given_httpHealthCheckUrl_when_healthCheckValidationError_then_error() {
        val config =
            HermesConfig(
                endpoint = HTTPS_ENDPOINT,
                healthCheckUrl = "http://hermes.example/health",
            )

        assertNotNull(config.healthCheckValidationError())
    }

    @Test
    fun given_userInfoHealthCheckUrl_when_healthCheckValidationError_then_error() {
        val config =
            HermesConfig(
                endpoint = HTTPS_ENDPOINT,
                healthCheckUrl = "https://user:pass@hermes.example/health",
            )

        assertNotNull(config.healthCheckValidationError())
    }

    @Test
    fun given_validHttpsHealthCheckUrl_when_healthCheckValidationError_then_null() {
        val config = HermesConfig(endpoint = HTTPS_ENDPOINT, healthCheckUrl = HTTPS_HEALTH)

        assertNull(config.healthCheckValidationError())
    }

    @Test
    fun given_healthCheckUrlEqualToEndpoint_when_healthCheckValidationError_then_error() {
        val config = HermesConfig(endpoint = HTTPS_ENDPOINT, healthCheckUrl = HTTPS_ENDPOINT)

        assertNotNull(config.healthCheckValidationError())
    }

    @Test
    fun given_healthCheckUrlEqualToEndpointIgnoringSlashAndSpaces_when_validated_then_error() {
        val config =
            HermesConfig(
                endpoint = HTTPS_ENDPOINT,
                healthCheckUrl = "  $HTTPS_ENDPOINT/  ",
            )

        assertNotNull(config.healthCheckValidationError())
    }

    private companion object {
        const val HTTPS_ENDPOINT = "https://hermes.example/sync"
        const val HTTPS_HEALTH = "https://hermes.example/health"
    }
}
