package com.buzzbike.app.domain.sync

import java.net.MalformedURLException
import java.net.URL

/**
 * Configuration for the optional Hermes sync.
 *
 * Defaults are conservative: 30 s timeout, 3 retries and webhook transport.
 * Both [endpoint] and [healthCheckUrl] must be valid HTTPS URLs; the sync is
 * considered misconfigured until the user provides a dedicated health endpoint
 * (there is no fallback to [endpoint]). The domain validates format only and
 * never opens a connection.
 *
 * @property retryCount Number of delivery attempts granted to each outbox row
 *   (`maxAttempts`). Values below 1 are coerced to 1 by the enqueue path so a
 *   ride always gets at least one attempt.
 * @property senderType Semantic receiver contract ([SenderType]), not a
 *   transport switch: both modes use `POST` over HTTPS with the same HMAC
 *   headers (see `docs/sync-contract.md` §1).
 */
data class HermesConfig(
    val endpoint: String,
    val method: String = "POST",
    val timeoutSeconds: Int = 30,
    val retryCount: Int = 3,
    val senderType: SenderType = SenderType.WEBHOOK,
    /**
     * Dedicated health-check URL. There is intentionally **no fallback** to
     * [endpoint]: the send endpoint must never be probed as a health check.
     * See [effectiveHealthCheckUrl] and [healthCheckValidationError].
     */
    val healthCheckUrl: String? = null,
) {
    /**
     * URL the connectivity check should probe, or `null` when none is usable.
     *
     * Only [healthCheckUrl] is considered (trimmed, non-blank). A missing or
     * blank value yields `null`, which the worker treats as a configuration
     * error instead of guessing an alternative URL.
     */
    fun effectiveHealthCheckUrl(): String? = healthCheckUrl?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Validates [healthCheckUrl] with the same rules as [endpoint]. A missing
     * or blank value is also an error: sync cannot run without a dedicated,
     * authenticated health endpoint.
     */
    fun healthCheckValidationError(): String? {
        val url =
            healthCheckUrl?.trim()?.takeIf { it.isNotBlank() }
                ?: return "health check URL must not be blank"
        return healthCheckUrlValidationError(url)
    }
}

/**
 * Semantic receiver contract, **not** a transport difference.
 *
 * Both values produce the same wire behaviour: `POST` over HTTPS with the
 * `X-Webhook-Timestamp` / `X-Webhook-Signature-V2` HMAC headers. The value only
 * tells the receiver which route/contract to expect, so it never changes the
 * headers or the signing input. Kept as an enum so the intent stays explicit
 * and future receiver contracts (if any) have a place to live.
 */
enum class SenderType {
    WEBHOOK,
    REST,
}

private const val HTTPS_SCHEME = "https"

/**
 * Validates a sync endpoint for a Hermes transport.
 *
 * Rules (same for the send endpoint and the dedicated health-check URL):
 * - scheme must be `https`; cleartext `http` is rejected (AGENTS.md §7);
 * - a host must be present;
 * - userinfo (`user:pass@`) is rejected so credentials never hide in a URL;
 * - query strings (`?token=...`) and fragments (`#...`) are rejected: the
 *   sync endpoints do not need them, and keeping the URL clean prevents a
 *   token from being persisted in `WorkManager` input data.
 *
 * Private ranges and CGNAT are deliberately **not** rejected: the supported
 * deployment reaches the NAS over Tailscale, which uses `100.64.0.0/10`
 * (CGNAT) and MagicDNS hostnames (`*.ts.net`). Blocking those ranges would
 * break the only intended network topology (ADR-003).
 *
 * Only the format is checked: [URL] parses the string without opening a
 * socket. Returns `null` when valid, or a stable, secret-free error message
 * otherwise.
 */
internal fun endpointValidationError(endpoint: String): String? = urlValidationError(endpoint, "endpoint")

/** Same rules as [endpointValidationError], labelled for the health check. */
internal fun healthCheckUrlValidationError(url: String): String? = urlValidationError(url, "health check URL")

private fun urlValidationError(
    value: String,
    label: String,
): String? {
    if (value.isBlank()) {
        return "$label must not be blank"
    }
    return try {
        val url = URL(value)
        when {
            !url.protocol.equals(HTTPS_SCHEME, ignoreCase = true) ->
                "$label protocol must be https"
            url.host.isNullOrBlank() -> "$label must include a host"
            !url.userInfo.isNullOrBlank() -> "$label must not include user info"
            url.query != null -> "$label must not include a query string"
            url.ref != null -> "$label must not include a fragment"
            else -> null
        }
    } catch (ignored: MalformedURLException) {
        "$label is not a valid URL"
    }
}
