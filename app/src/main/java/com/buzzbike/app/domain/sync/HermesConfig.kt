package com.buzzbike.app.domain.sync

import java.net.MalformedURLException
import java.net.URL

/**
 * Configuration for the optional Hermes sync.
 *
 * Defaults are conservative: 30 s timeout, 3 retries and webhook transport.
 * [endpoint] is validated by [endpointValidationError] before any transport is
 * built; validation never opens a connection.
 */
data class HermesConfig(
    val endpoint: String,
    val method: String = "POST",
    val timeoutSeconds: Int = 30,
    val retryCount: Int = 3,
    val senderType: SenderType = SenderType.WEBHOOK,
    /**
     * Optional dedicated health-check URL. When blank or absent the sync
     * stack falls back to [endpoint] — see [effectiveHealthCheckUrl].
     */
    val healthCheckUrl: String? = null,
) {
    /**
     * URL the connectivity check should probe, or `null` when none is usable.
     *
     * A non-blank [healthCheckUrl] wins over [endpoint]; a blank optional URL
     * is treated as "not configured" instead of failing the check.
     */
    fun effectiveHealthCheckUrl(): String? =
        healthCheckUrl?.takeIf { it.isNotBlank() }
            ?: endpoint.takeIf { it.isNotBlank() }
}

/** Transport used to reach the Hermes Agent. */
enum class SenderType {
    WEBHOOK,
    REST,
}

private val SUPPORTED_PROTOCOLS = setOf("http", "https")

/**
 * Validates [endpoint] for a Hermes transport.
 *
 * Only the format is checked: [URL] parses the string without opening a socket
 * (slice 3.1 is network-free by design). Returns `null` when valid, or a
 * stable, secret-free error message otherwise.
 */
internal fun endpointValidationError(endpoint: String): String? {
    if (endpoint.isBlank()) {
        return "endpoint must not be blank"
    }
    return try {
        val url = URL(endpoint)
        when {
            url.protocol !in SUPPORTED_PROTOCOLS -> "endpoint protocol must be http or https"
            url.host.isNullOrBlank() -> "endpoint must include a host"
            else -> null
        }
    } catch (ignored: MalformedURLException) {
        "endpoint is not a valid URL"
    }
}
