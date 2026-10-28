package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM tests for [HttpHermesSender] against a real, ephemeral [HttpServer] on
 * the loopback interface.
 *
 * They pin ADR-003's wire contract: the request carries
 * `X-Webhook-Signature-V2` = hex HMAC-SHA256 over `"<timestamp>." + body`,
 * plus the matching `X-Webhook-Timestamp` and `X-Ride-Id`. The expected
 * signature is computed independently with [Mac], never by the signer under
 * test. They also pin the delivery success rule (any 2xx), the refusal to
 * follow redirects and the promise that network failures return a
 * [com.buzzbike.app.domain.sync.SendResult] instead of throwing.
 */
class HttpHermesSenderTest {
    private val servers = mutableListOf<HttpServer>()
    private val executors = mutableListOf<ExecutorService>()

    @After
    fun tearDown() {
        servers.forEach { it.stop(0) }
        executors.forEach { it.shutdownNow() }
    }

    @Test
    fun given_serverReturns200_when_sendRide_then_successAndSignedRequest() =
        runTest {
            val captured = AtomicReference<CapturedRequest>()
            val server =
                startServer(HTTP_OK) { exchange ->
                    captured.set(
                        CapturedRequest(
                            method = exchange.requestMethod,
                            contentType = exchange.requestHeaders.getFirst(HEADER_CONTENT_TYPE),
                            timestamp = exchange.requestHeaders.getFirst(HEADER_TIMESTAMP),
                            signature = exchange.requestHeaders.getFirst(HEADER_SIGNATURE),
                            rideId = exchange.requestHeaders.getFirst(HEADER_RIDE_ID),
                            body = exchange.requestBody.readBytes(),
                        ),
                    )
                }

            val result = senderFor(webhookUrl(server)).sendRide(RIDE_ID, PAYLOAD)

            assertTrue(result.success)
            assertEquals(HTTP_OK, result.statusCode)
            val request = captured.get()
            assertEquals(HTTP_POST, request.method)
            assertEquals(CONTENT_TYPE_JSON, request.contentType)
            assertEquals(TIMESTAMP.toString(), request.timestamp)
            assertEquals(expectedSignature(TIMESTAMP, PAYLOAD), request.signature)
            assertEquals(RIDE_ID, request.rideId)
            assertArrayEquals(PAYLOAD, request.body)
        }

    @Test
    fun given_serverReturns500_when_sendRide_then_failureWithStatus() =
        runTest {
            val server = startServer(HTTP_SERVER_ERROR)

            val result = senderFor(webhookUrl(server)).sendRide(RIDE_ID, PAYLOAD)

            assertFalse(result.success)
            assertEquals(HTTP_SERVER_ERROR, result.statusCode)
        }

    @Test
    fun given_serverReturns204_when_sendRide_then_success() =
        runTest {
            val server = startServer(HTTP_NO_CONTENT)

            val result = senderFor(webhookUrl(server)).sendRide(RIDE_ID, PAYLOAD)

            assertTrue(result.success)
            assertEquals(HTTP_NO_CONTENT, result.statusCode)
        }

    @Test
    fun given_serverReturns302_when_sendRide_then_failureAndRedirectNotFollowed() =
        runTest {
            val server =
                startServer(HTTP_REDIRECT) { exchange ->
                    exchange.responseHeaders.add(HEADER_LOCATION, REDIRECT_LOCATION)
                }

            val result = senderFor(webhookUrl(server)).sendRide(RIDE_ID, PAYLOAD)

            assertFalse(result.success)
            assertEquals(HTTP_REDIRECT, result.statusCode)
        }

    @Test
    fun given_closedPort_when_sendRide_then_failureWithoutExceptionOrUrlLeak() =
        runTest {
            val endpoint = "http://$LOOPBACK:${findFreePort()}$WEBHOOK_PATH"

            val result = senderFor(endpoint).sendRide(RIDE_ID, PAYLOAD)

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertNotNull(result.errorMessage)
            assertFalse(result.errorMessage.orEmpty().contains(LOOPBACK))
        }

    @Test
    fun given_differentPayloads_when_sendRideTwice_then_signaturesDiffer() =
        runTest {
            val signatures = CopyOnWriteArrayList<String>()
            val server =
                startServer(HTTP_OK) { exchange ->
                    exchange.requestBody.readBytes()
                    exchange.requestHeaders.getFirst(HEADER_SIGNATURE)?.let(signatures::add)
                }
            val sender = senderFor(webhookUrl(server))

            sender.sendRide(RIDE_ID, PAYLOAD)
            sender.sendRide(RIDE_ID, ALTERNATE_PAYLOAD)

            assertEquals(SIGNATURE_COUNT, signatures.size)
            assertNotEquals(signatures[0], signatures[1])
            assertEquals(expectedSignature(TIMESTAMP, PAYLOAD), signatures[0])
            assertEquals(expectedSignature(TIMESTAMP, ALTERNATE_PAYLOAD), signatures[1])
        }

    private fun startServer(
        responseCode: Int,
        onRequest: (HttpExchange) -> Unit = {},
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress(LOOPBACK, EPHEMERAL_PORT), 0)
        val executor =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable).apply { isDaemon = true }
            }
        server.executor = executor
        server.createContext(
            WEBHOOK_PATH,
            HttpHandler { exchange ->
                try {
                    onRequest(exchange)
                    exchange.sendResponseHeaders(responseCode, NO_RESPONSE_BODY)
                } finally {
                    exchange.close()
                }
            },
        )
        server.start()
        servers += server
        executors += executor
        return server
    }

    private fun senderFor(endpoint: String): HttpHermesSender =
        HttpHermesSender(
            config = HermesConfig(endpoint = endpoint),
            hmacSigner = HmacSha256Signer(FakeHmacKeyProvider(KEY)),
            nowSeconds = { TIMESTAMP },
        )

    private fun webhookUrl(server: HttpServer): String = "http://$LOOPBACK:${server.address.port}$WEBHOOK_PATH"

    /** Independent HMAC-SHA256 over `"<timestamp>." + payload`, lowercase hex. */
    private fun expectedSignature(
        timestamp: Long,
        payload: ByteArray,
    ): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(KEY, ALGORITHM))
        mac.update("$timestamp.".toByteArray(StandardCharsets.UTF_8))
        mac.update(payload)
        return mac.doFinal().joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private fun findFreePort(): Int = ServerSocket(EPHEMERAL_PORT).use { it.localPort }

    private data class CapturedRequest(
        val method: String,
        val contentType: String?,
        val timestamp: String?,
        val signature: String?,
        val rideId: String?,
        val body: ByteArray,
    )

    private class FakeHmacKeyProvider(
        private val key: ByteArray,
    ) : HmacKeyProvider {
        override fun getOrCreateKey(): SecretKey = SecretKeySpec(key, ALGORITHM)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val EPHEMERAL_PORT = 0
        const val WEBHOOK_PATH = "/webhook"
        const val REDIRECT_LOCATION = "/ignored"
        const val NO_RESPONSE_BODY = -1L
        const val HTTP_OK = 200
        const val HTTP_NO_CONTENT = 204
        const val HTTP_REDIRECT = 302
        const val HTTP_SERVER_ERROR = 500
        const val HTTP_POST = "POST"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_TIMESTAMP = "X-Webhook-Timestamp"
        const val HEADER_SIGNATURE = "X-Webhook-Signature-V2"
        const val HEADER_RIDE_ID = "X-Ride-Id"
        const val HEADER_LOCATION = "Location"
        const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        const val SIGNATURE_COUNT = 2
        const val TIMESTAMP = 1_700_000_000L
        const val RIDE_ID = "ride-123"
        const val ALGORITHM = "HmacSHA256"
        val KEY = "test-secret".toByteArray(StandardCharsets.UTF_8)
        val PAYLOAD = """{"rideId":"ride-123","distanceMeters":1234.5}""".toByteArray(StandardCharsets.UTF_8)
        val ALTERNATE_PAYLOAD = """{"rideId":"ride-123","distanceMeters":9999.9}""".toByteArray(StandardCharsets.UTF_8)
    }
}
