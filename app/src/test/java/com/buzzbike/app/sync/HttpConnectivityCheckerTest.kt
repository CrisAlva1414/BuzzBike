package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.buzzbike.app.domain.sync.HmacSecretMissingException
import com.buzzbike.app.domain.sync.HmacSigner
import com.buzzbike.app.domain.sync.Reachability
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM tests for [HttpConnectivityChecker] against a real, ephemeral
 * [HttpServer] on the loopback interface.
 *
 * They pin the product rule: **only HTTP 200 exactly, for an authenticated
 * request, counts as reachable**. The server side verifies the HMAC headers
 * independently with [Mac] (never with the signer under test) over an empty
 * body. Every other status, refusal, timeout or malformed URL must be mapped
 * to [Reachability.Unavailable] or [Reachability.Misconfigured] without
 * throwing.
 */
class HttpConnectivityCheckerTest {
    private val servers = mutableListOf<HttpServer>()
    private val executors = mutableListOf<ExecutorService>()

    @After
    fun tearDown() {
        servers.forEach { it.stop(0) }
        executors.forEach { it.shutdownNow() }
    }

    @Test
    fun given_healthCheckReturns200AndValidSignature_when_check_then_available() =
        runTest {
            val captured = AtomicReference<CapturedRequest>()
            val server =
                startServer(HTTP_OK_CODE) { exchange ->
                    captured.set(
                        CapturedRequest(
                            method = exchange.requestMethod,
                            timestamp = exchange.requestHeaders.getFirst(HEADER_TIMESTAMP),
                            signature = exchange.requestHeaders.getFirst(HEADER_SIGNATURE),
                        ),
                    )
                }

            val reachability = checker().check(healthUrl(server), hmacKeyConfigured = true)

            assertEquals(Reachability.Available, reachability)
            val request = captured.get()
            assertEquals(HTTP_GET, request.method)
            assertEquals(TIMESTAMP.toString(), request.timestamp)
            assertEquals(expectedSignature(TIMESTAMP, EMPTY_BODY), request.signature)
        }

    @Test
    fun given_missingSecret_when_check_then_misconfiguredWithoutNetwork() =
        runTest {
            val requests = AtomicInteger(0)
            val server =
                startServer(HTTP_OK_CODE) { _ ->
                    requests.incrementAndGet()
                }

            val reachability = checker().check(healthUrl(server), hmacKeyConfigured = false)

            assertEquals(Reachability.Misconfigured, reachability)
            assertEquals(0, requests.get())
        }

    @Test
    fun given_signerThrowsSecretMissing_when_check_then_misconfigured() =
        runTest {
            val reachability =
                checker(hmacSigner = MissingSecretSigner())
                    .check(HEALTH_URL, hmacKeyConfigured = true)

            assertEquals(Reachability.Misconfigured, reachability)
        }

    @Test
    fun given_healthCheckReturns204_when_check_then_unavailable() =
        runTest {
            val server = startServer(HTTP_NO_CONTENT_CODE)

            assertEquals(Reachability.Unavailable, checker().check(healthUrl(server), true))
        }

    @Test
    fun given_healthCheckReturns302_when_check_then_unavailable() =
        runTest {
            val server = startServer(HTTP_REDIRECT_CODE)

            assertEquals(Reachability.Unavailable, checker().check(healthUrl(server), true))
        }

    @Test
    fun given_healthCheckReturns500_when_check_then_unavailable() =
        runTest {
            val server = startServer(HTTP_SERVER_ERROR_CODE)

            assertEquals(Reachability.Unavailable, checker().check(healthUrl(server), true))
        }

    @Test
    fun given_healthCheckReturns401_when_check_then_misconfigured() =
        runTest {
            val server = startServer(HTTP_UNAUTHORIZED_CODE)

            assertEquals(Reachability.Misconfigured, checker().check(healthUrl(server), true))
        }

    @Test
    fun given_healthCheckReturns403_when_check_then_misconfigured() =
        runTest {
            val server = startServer(HTTP_FORBIDDEN_CODE)

            assertEquals(Reachability.Misconfigured, checker().check(healthUrl(server), true))
        }

    @Test
    fun given_insecureUrlAndDefaultChecker_when_check_then_misconfiguredWithoutNetwork() =
        runTest {
            val requests = AtomicInteger(0)
            val server =
                startServer(HTTP_OK_CODE) { _ ->
                    requests.incrementAndGet()
                }
            val strictChecker =
                HttpConnectivityChecker(
                    hmacSigner = HmacSha256Signer(FakeHmacKeyProvider(KEY)),
                    nowSeconds = { TIMESTAMP },
                )

            val reachability = strictChecker.check(healthUrl(server), hmacKeyConfigured = true)

            assertEquals(Reachability.Misconfigured, reachability)
            assertEquals(0, requests.get())
        }

    @Test
    fun given_malformedUrl_when_check_then_misconfigured() =
        runTest {
            assertEquals(Reachability.Misconfigured, checker().check("not-a-url", true))
        }

    @Test
    fun given_blankUrl_when_check_then_misconfigured() =
        runTest {
            assertEquals(Reachability.Misconfigured, checker().check("   ", true))
        }

    @Test
    fun given_closedPort_when_check_then_unavailable() =
        runTest {
            val url = "http://$LOOPBACK:${findFreePort()}$HEALTH_PATH"

            assertEquals(Reachability.Unavailable, checker().check(url, true))
        }

    @Test
    fun given_serverExceedsReadTimeout_when_check_then_unavailable() =
        runTest {
            val releaseHandler = CountDownLatch(1)
            val server =
                startServer { _ ->
                    releaseHandler.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            val checker =
                checker(
                    connectTimeoutMillis = SHORT_TIMEOUT_MILLIS,
                    readTimeoutMillis = SHORT_TIMEOUT_MILLIS,
                )

            val reachability = checker.check(healthUrl(server), true)
            releaseHandler.countDown()

            assertEquals(Reachability.Unavailable, reachability)
        }

    private fun checker(
        hmacSigner: HmacSigner = HmacSha256Signer(FakeHmacKeyProvider(KEY)),
        connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
        allowInsecureForTests: Boolean = true,
    ): HttpConnectivityChecker =
        HttpConnectivityChecker(
            hmacSigner = hmacSigner,
            nowSeconds = { TIMESTAMP },
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            allowInsecureForTests = allowInsecureForTests,
        )

    private fun startServer(responseCode: Int): HttpServer =
        startServer { exchange ->
            exchange.sendResponseHeaders(responseCode, NO_RESPONSE_BODY)
        }

    private fun startServer(
        responseCode: Int,
        handler: (HttpExchange) -> Unit,
    ): HttpServer =
        startServer { exchange ->
            handler(exchange)
            exchange.sendResponseHeaders(responseCode, NO_RESPONSE_BODY)
        }

    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress(LOOPBACK, EPHEMERAL_PORT), 0)
        val executor =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable).apply { isDaemon = true }
            }
        server.executor = executor
        server.createContext(
            HEALTH_PATH,
            HttpHandler { exchange ->
                try {
                    handler(exchange)
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

    private fun healthUrl(server: HttpServer): String = "http://$LOOPBACK:${server.address.port}$HEALTH_PATH"

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
        val timestamp: String?,
        val signature: String?,
    )

    private class FakeHmacKeyProvider(
        private val key: ByteArray,
    ) : HmacKeyProvider {
        override fun getOrCreateKey(): SecretKey = SecretKeySpec(key, ALGORITHM)
    }

    private class MissingSecretSigner : HmacSigner {
        override fun sign(
            timestampSeconds: Long,
            payload: ByteArray,
        ): String = throw HmacSecretMissingException()

        override fun verify(
            timestampSeconds: Long,
            payload: ByteArray,
            signature: String,
        ): Boolean = false
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val EPHEMERAL_PORT = 0
        const val HEALTH_PATH = "/health"
        const val HEALTH_URL = "https://hermes.example/health"
        const val NO_RESPONSE_BODY = -1L
        const val HTTP_OK_CODE = 200
        const val HTTP_NO_CONTENT_CODE = 204
        const val HTTP_REDIRECT_CODE = 302
        const val HTTP_UNAUTHORIZED_CODE = 401
        const val HTTP_FORBIDDEN_CODE = 403
        const val HTTP_SERVER_ERROR_CODE = 500
        const val SHORT_TIMEOUT_MILLIS = 200
        const val DEFAULT_TIMEOUT_MILLIS = 5_000
        const val RELEASE_TIMEOUT_SECONDS = 5L
        const val TIMESTAMP = 1_700_000_000L
        const val HTTP_GET = "GET"
        const val HEADER_TIMESTAMP = "X-Webhook-Timestamp"
        const val HEADER_SIGNATURE = "X-Webhook-Signature-V2"
        const val ALGORITHM = "HmacSHA256"
        val KEY = "test-secret".toByteArray(StandardCharsets.UTF_8)
        val EMPTY_BODY = ByteArray(0)
    }
}
