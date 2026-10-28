package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.SenderType

/**
 * Serializes [HermesConfig] to and from the flat `Map<String, String>` that
 * WorkManager's [androidx.work.Data] transport requires.
 *
 * This codec is deliberately Android-free: WorkManager only accepts primitive
 * values as input data, and keeping the mapping here lets the transport be
 * tested on the JVM. Only transport configuration travels through the map —
 * never an HMAC secret, a token, or a ride payload (AGENTS.md §7).
 */
object HermesConfigCodec {
    const val KEY_ENDPOINT = "endpoint"
    const val KEY_HEALTH_CHECK_URL = "health_check_url"
    const val KEY_METHOD = "method"
    const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
    const val KEY_RETRY_COUNT = "retry_count"
    const val KEY_SENDER_TYPE = "sender_type"

    /** Flattens [config] into WorkManager-safe string values. */
    fun encode(config: HermesConfig): Map<String, String> =
        mapOf(
            KEY_ENDPOINT to config.endpoint,
            KEY_HEALTH_CHECK_URL to config.healthCheckUrl.orEmpty(),
            KEY_METHOD to config.method,
            KEY_TIMEOUT_SECONDS to config.timeoutSeconds.toString(),
            KEY_RETRY_COUNT to config.retryCount.toString(),
            KEY_SENDER_TYPE to config.senderType.name,
        )

    /**
     * Rebuilds a [HermesConfig] from [map].
     *
     * Returns `null` when the endpoint or the dedicated health-check URL is
     * missing or blank: without either there is no safe sync destination (the
     * send endpoint is never probed as a health check), so the worker must
     * fail instead of guessing. Every other field falls back to the
     * [HermesConfig] defaults when absent or unparseable; an unknown
     * [SenderType] falls back to `WEBHOOK` because that is the safest
     * transport.
     */
    fun decode(map: Map<String, String>): HermesConfig? {
        val endpoint = map[KEY_ENDPOINT]?.trim().orEmpty()
        val healthCheckUrl = map[KEY_HEALTH_CHECK_URL]?.trim().orEmpty()
        if (endpoint.isBlank() || healthCheckUrl.isBlank()) return null

        val defaults = HermesConfig(endpoint = endpoint, healthCheckUrl = healthCheckUrl)
        return HermesConfig(
            endpoint = endpoint,
            method = map[KEY_METHOD]?.takeIf { it.isNotBlank() } ?: defaults.method,
            timeoutSeconds = map[KEY_TIMEOUT_SECONDS].toPositiveIntOrNull() ?: defaults.timeoutSeconds,
            retryCount = map[KEY_RETRY_COUNT].toNonNegativeIntOrNull() ?: defaults.retryCount,
            senderType = map[KEY_SENDER_TYPE].toSenderTypeOrDefault(),
            healthCheckUrl = healthCheckUrl,
        )
    }

    private fun String?.toPositiveIntOrNull(): Int? = this?.toIntOrNull()?.takeIf { it > 0 }

    private fun String?.toNonNegativeIntOrNull(): Int? = this?.toIntOrNull()?.takeIf { it >= 0 }

    private fun String?.toSenderTypeOrDefault(): SenderType =
        SenderType.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: SenderType.WEBHOOK
}
