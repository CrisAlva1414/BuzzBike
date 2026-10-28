package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.ConnectivityChecker
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
 * A health check is considered successful **only** when the server answers
 * HTTP 200 exactly. This is a product requirement: 204, 3xx, 4xx, 5xx, a
 * timeout, a DNS failure or a refused connection all mean "not available", so
 * the caller keeps the ride in the outbox and retries later instead of
 * dropping it.
 *
 * Redirects are not followed (`instanceFollowRedirects = false`): a 302 is
 * itself a non-200 answer and must not be transformed into a 200 downstream.
 *
 * Android note: the app's `network_security_config` blocks cleartext traffic,
 * so the health-check URL must be HTTPS (ADR-003, AGENTS.md §7). A cleartext
 * URL fails with an `IOException` and therefore reports `false`.
 *
 * The check never throws: expected network problems are part of the answer.
 */
class HttpConnectivityChecker(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : ConnectivityChecker {
    override suspend fun isReachable(healthCheckUrl: String): Boolean =
        withContext(Dispatchers.IO) { probe(healthCheckUrl) }

    /** Performs the blocking probe; extracted so no labeled return is needed. */
    private fun probe(healthCheckUrl: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(healthCheckUrl).openConnection()
            if (opened !is HttpURLConnection) {
                false
            } else {
                connection = opened
                opened.requestMethod = HTTP_GET
                opened.instanceFollowRedirects = false
                opened.connectTimeout = connectTimeoutMillis
                opened.readTimeout = readTimeoutMillis
                val responseCode = opened.responseCode
                closeResponseStream(opened, responseCode)
                responseCode == HTTP_OK
            }
        } catch (ignored: MalformedURLException) {
            false
        } catch (ignored: IOException) {
            false
        } catch (ignored: SecurityException) {
            false
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
        const val HTTP_OK = 200
        const val CLIENT_ERROR_MIN = 400
        const val DEFAULT_TIMEOUT_MILLIS = 5_000
    }
}
