package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HermesSender
import com.buzzbike.app.domain.sync.HmacSecretMissingException
import com.buzzbike.app.domain.sync.HmacSigner
import com.buzzbike.app.domain.sync.SendResult
import com.buzzbike.app.domain.sync.endpointValidationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [HermesSender] backed by [HttpURLConnection] — the real transport for the
 * optional Hermes sync defined in ADR-003.
 *
 * The endpoint is validated **before** any socket is opened: it must be an
 * HTTPS URL with a host and without `user:pass@` userinfo. An invalid
 * configuration returns [SendResult] with `"invalid endpoint configuration"`
 * and never echoes the URL, which could contain credentials.
 *
 * Every request is signed with HMAC-SHA256 over `"<timestamp>." + payload`
 * (ADR-003 §"Firma HMAC V2"). The signature travels in
 * `X-Webhook-Signature-V2` as lowercase hex, next to the matching
 * `X-Webhook-Timestamp` (Unix epoch seconds). The ride identifier travels
 * only inside the signed body, so the receiver deduplicates by the body's
 * `rideId` and never trusts an unsigned header. The receiver can therefore
 * reject replayed or tampered payloads without trusting the body itself.
 *
 * **Success rule:** delivery succeeds on **any 2xx** status (`200..299`). This
 * differs from [HttpConnectivityChecker], which requires **exactly 200**:
 * a health probe answers "is the agent reachable at all?", while a ride
 * delivery only needs the receiver to accept it, and `202 Accepted` or
 * `204 No Content` are valid acknowledgements.
 *
 * Redirects are not followed (`instanceFollowRedirects = false`): a 3xx is a
 * non-2xx answer and is reported as a failure so the outbox keeps the ride.
 *
 * Timeouts come from [HermesConfig.timeoutSeconds] and are clamped to at least
 * one second so a `0` or negative value can never mean "wait forever" (R4).
 *
 * Expected transport problems (bad URL, DNS/connect failure, timeout, refused
 * connection, blocked cleartext) are returned as [SendResult] failures instead
 * of being thrown. Error messages never include the endpoint, because the URL
 * may carry credentials in its query string.
 */
class HttpHermesSender(
    private val config: HermesConfig,
    private val hmacSigner: HmacSigner,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) : HermesSender {
    private val timeoutMillis: Int = resolveTimeoutMillis(config.timeoutSeconds)

    // The ride id is already part of the signed payload, so the header was
    // removed (docs/sync-contract.md §3). The parameter stays for the port.
    @Suppress("UNUSED_PARAMETER")
    override suspend fun sendRide(
        rideId: String,
        payload: ByteArray,
    ): SendResult = withContext(Dispatchers.IO) { deliver(payload) }

    /** Performs the blocking request; extracted so [sendRide] only switches context. */
    private fun deliver(payload: ByteArray): SendResult {
        if (endpointValidationError(config.endpoint) != null) {
            return SendResult(success = false, errorMessage = INVALID_CONFIGURATION_MESSAGE)
        }
        val timestamp = nowSeconds()
        return try {
            deliverSigned(payload, timestamp, hmacSigner.sign(timestamp, payload))
        } catch (expected: HmacSecretMissingException) {
            // Sync is opt-in and the secret is user-provisioned; a missing
            // secret is a configuration state, not a transport failure. It is
            // reported without leaking any secret material.
            SendResult(success = false, errorMessage = HMAC_SECRET_MISSING_MESSAGE)
        }
    }

    private fun deliverSigned(
        payload: ByteArray,
        timestamp: Long,
        signature: String,
    ): SendResult {
        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(config.endpoint).openConnection()
            val http =
                opened as? HttpURLConnection
                    ?: return SendResult(success = false, errorMessage = UNSUPPORTED_TRANSPORT_MESSAGE)
            connection = http
            http.requestMethod = config.method.takeIf { it.isNotBlank() } ?: HTTP_POST
            http.doOutput = true
            http.instanceFollowRedirects = false
            http.connectTimeout = timeoutMillis
            http.readTimeout = timeoutMillis
            http.setRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            http.setRequestProperty(HEADER_TIMESTAMP, timestamp.toString())
            http.setRequestProperty(HEADER_SIGNATURE, signature)
            http.outputStream.use { it.write(payload) }
            val responseCode = http.responseCode
            drainAndClose(http, responseCode)
            SendResult(
                success = responseCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX,
                statusCode = responseCode,
            )
        } catch (ignored: IllegalArgumentException) {
            SendResult(success = false, errorMessage = INVALID_ENDPOINT_MESSAGE)
        } catch (ignored: IOException) {
            SendResult(success = false, errorMessage = NETWORK_ERROR_MESSAGE)
        } catch (ignored: SecurityException) {
            SendResult(success = false, errorMessage = SECURITY_ERROR_MESSAGE)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Reads and closes the response body so [HttpURLConnection] can release
     * the underlying socket. Error responses expose the body through
     * [HttpURLConnection.errorStream]; reading `inputStream` there would throw.
     *
     * Best-effort: a failure while draining must not turn a known HTTP status
     * into an exception, so it is ignored.
     */
    private fun drainAndClose(
        connection: HttpURLConnection,
        responseCode: Int,
    ) {
        val stream =
            if (responseCode >= CLIENT_ERROR_MIN) {
                connection.errorStream
            } else {
                connection.inputStream
            }
        try {
            stream?.use { input ->
                val buffer = ByteArray(DRAIN_BUFFER_SIZE)
                while (input.read(buffer) != END_OF_STREAM) {
                    // Discard: the receiver's body is not part of the contract.
                }
            }
        } catch (ignored: IOException) {
            // The status code is already known; draining is only cleanup.
        }
    }

    private companion object {
        const val HTTP_POST = "POST"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_TIMESTAMP = "X-Webhook-Timestamp"
        const val HEADER_SIGNATURE = "X-Webhook-Signature-V2"
        const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        const val HTTP_SUCCESS_MIN = 200
        const val HTTP_SUCCESS_MAX = 299
        const val CLIENT_ERROR_MIN = 400
        const val END_OF_STREAM = -1
        const val DRAIN_BUFFER_SIZE = 4_096
        const val MILLIS_PER_SECOND = 1_000L
        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_MILLIS = Int.MAX_VALUE.toLong()
        const val UNSUPPORTED_TRANSPORT_MESSAGE = "endpoint does not support HTTP"
        const val INVALID_CONFIGURATION_MESSAGE = "invalid endpoint configuration"
        const val INVALID_ENDPOINT_MESSAGE = "endpoint is not a valid URL"
        const val NETWORK_ERROR_MESSAGE = "network error while sending ride"
        const val SECURITY_ERROR_MESSAGE = "security error while sending ride"
        const val HMAC_SECRET_MISSING_MESSAGE = "HMAC secret is not configured"

        fun resolveTimeoutMillis(timeoutSeconds: Int): Int =
            (timeoutSeconds.coerceAtLeast(MIN_TIMEOUT_SECONDS).toLong() * MILLIS_PER_SECOND)
                .coerceAtMost(MAX_TIMEOUT_MILLIS)
                .toInt()
    }
}
