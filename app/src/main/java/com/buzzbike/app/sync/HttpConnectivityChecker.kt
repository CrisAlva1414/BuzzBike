package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.ConnectivityChecker
import com.buzzbike.app.domain.sync.HmacSecretMissingException
import com.buzzbike.app.domain.sync.HmacSigner
import com.buzzbike.app.domain.sync.Reachability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * [ConnectivityChecker] backed by [HttpURLConnection] — the `sync/` edge
 * adapter that owns the transport details (AGENTS.md §3).
 *
 * The health endpoint is **dedicated and authenticated**: it must accept a
 * `GET` signed with HMAC-SHA256 over an empty body using the same secret as
 * regular deliveries. The request carries [HEADER_TIMESTAMP] (Unix epoch
 * seconds) and [HEADER_SIGNATURE] (`hmacSigner.sign(timestamp, ByteArray(0))`),
 * so a receiver that verifies the signature can distinguish BuzzBike from
 * anonymous probes. Signing an empty body reuses the exact `<timestamp>.` input
 * defined in ADR-003.
 *
 * A health check is considered successful **only** when the server answers
 * HTTP 200 exactly. `401` and `403` mean the configured secret does not match
 * (or the receiver did not authenticate the request), so they are reported as
 * [Reachability.Misconfigured]: retrying cannot fix bad credentials. `204`,
 * `3xx`, `5xx`, a timeout, a DNS failure or a refused connection all mean "not
 * available", so the caller keeps the ride in the outbox and retries later
 * instead of dropping it. A missing HMAC secret or a blank/malformed URL is
 * reported as [Reachability.Misconfigured] without opening a socket, because
 * retrying cannot fix either condition.
 *
 * Redirects are not followed (`instanceFollowRedirects = false`): a 302 is
 * itself a non-200 answer and must not be transformed into a 200 downstream.
 *
 * HTTPS is required by default: a cleartext `http://` URL is reported as
 * [Reachability.Misconfigured] without opening a socket (ADR-003, AGENTS.md
 * §7). [allowInsecureForTests] exists only so JVM tests can target a local
 * `HttpServer`; production wiring must keep the default.
 *
 * The check never throws: expected network problems are part of the answer.
 */
class HttpConnectivityChecker(
    private val hmacSigner: HmacSigner,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val allowInsecureForTests: Boolean = false,
) : ConnectivityChecker {
    override suspend fun check(
        healthCheckUrl: String,
        hmacKeyConfigured: Boolean,
    ): Reachability {
        val url = if (hmacKeyConfigured) parseUrl(healthCheckUrl) else null
        return when {
            url == null -> Reachability.Misconfigured
            !allowInsecureForTests && !url.protocol.equals(HTTPS_SCHEME, ignoreCase = true) ->
                Reachability.Misconfigured
            else -> withContext(Dispatchers.IO) { probe(url) }
        }
    }

    /** Parses [healthCheckUrl], returning `null` for blank or malformed input. */
    private fun parseUrl(healthCheckUrl: String): URL? {
        if (healthCheckUrl.isBlank()) return null
        return try {
            URL(healthCheckUrl.trim())
        } catch (ignored: MalformedURLException) {
            null
        }
    }

    /** Performs the blocking probe; extracted so no labeled return is needed. */
    private fun probe(url: URL): Reachability {
        var connection: HttpURLConnection? = null
        return try {
            val opened = url.openConnection()
            if (opened !is HttpURLConnection) {
                Reachability.Unavailable
            } else {
                connection = opened
                opened.requestMethod = HTTP_GET
                opened.instanceFollowRedirects = false
                opened.connectTimeout = connectTimeoutMillis
                opened.readTimeout = readTimeoutMillis
                val timestamp = nowSeconds()
                opened.setRequestProperty(HEADER_TIMESTAMP, timestamp.toString())
                opened.setRequestProperty(HEADER_SIGNATURE, hmacSigner.sign(timestamp, EMPTY_BODY))
                val responseCode = opened.responseCode
                closeResponseStream(opened, responseCode)
                when (responseCode) {
                    HTTP_OK -> Reachability.Available
                    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> Reachability.Misconfigured
                    else -> Reachability.Unavailable
                }
            }
        } catch (ignored: HmacSecretMissingException) {
            // The secret was reported as provisioned by the caller, but the
            // provider could not load it: still a configuration problem.
            Reachability.Misconfigured
        } catch (ignored: IOException) {
            Reachability.Unavailable
        } catch (ignored: SecurityException) {
            Reachability.Unavailable
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Closes the response stream so the socket can be released.
     *
     * Error responses expose their body through [HttpURLConnection.errorStream]
     * (reading [HttpURLConnection.inputStream] would throw); success and
     * redirect responses use the regular stream.
     */
    private fun closeResponseStream(
        connection: HttpURLConnection,
        responseCode: Int,
    ) {
        val stream =
            if (responseCode >= CLIENT_ERROR_MIN) {
                connection.errorStream
            } else {
                connection.inputStream
            }
        stream?.close()
    }

    private companion object {
        const val HTTP_GET = "GET"
        const val HTTPS_SCHEME = "https"
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val CLIENT_ERROR_MIN = 400
        const val HEADER_TIMESTAMP = "X-Webhook-Timestamp"
        const val HEADER_SIGNATURE = "X-Webhook-Signature-V2"
        const val DEFAULT_TIMEOUT_MILLIS = 5_000
        const val MILLIS_PER_SECOND = 1_000L
        val EMPTY_BODY = ByteArray(0)
    }
}
