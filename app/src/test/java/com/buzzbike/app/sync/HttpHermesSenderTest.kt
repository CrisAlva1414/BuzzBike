package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * JVM tests for [HttpHermesSender] against a real, ephemeral [HttpsServer] on
 * the loopback interface.
 *
 * HTTPS is mandatory now, so the tests use a throwaway self-signed test
 * keystore (no production material) and trust it only for the duration of each
 * test. They pin the wire contract: the request carries
 * `X-Webhook-Signature-V2` = hex HMAC-SHA256 over `"<timestamp>." + body`,
 * plus the matching `X-Webhook-Timestamp`. The ride id is not a header — it
 * travels inside the signed body. The expected signature is computed
 * independently with [Mac], never by the signer under test. They also pin the
 * delivery success rule (any 2xx), the refusal to follow redirects, endpoint
 * validation before connecting, config-driven
 * timeouts with a 1 s floor, and the promise that network failures return a
 * [com.buzzbike.app.domain.sync.SendResult] instead of throwing.
 */
class HttpHermesSenderTest {
    private val servers = mutableListOf<HttpsServer>()
    private val executors = mutableListOf<ExecutorService>()
    private lateinit var previousSocketFactory: SSLSocketFactory

    @Before
    fun trustTestCertificate() {
        previousSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(TestTls.sslContext().socketFactory)
    }

    @After
    fun tearDown() {
        HttpsURLConnection.setDefaultSSLSocketFactory(previousSocketFactory)
        servers.forEach { it.stop(0) }
        executors.forEach { it.shutdownNow() }
    }

    @Test
    fun given_serverReturns200_when_sendRide_then_successAndSignedRequestWithoutRideIdHeader() =
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
            assertNull(request.rideId)
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
            val endpoint = "https://$LOOPBACK:${findFreePort()}$WEBHOOK_PATH"

            val result = senderFor(endpoint).sendRide(RIDE_ID, PAYLOAD)

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertNotNull(result.errorMessage)
            assertFalse(result.errorMessage.orEmpty().contains(LOOPBACK))
        }

    @Test
    fun given_httpEndpoint_when_sendRide_then_failureBeforeConnectingWithoutUrlLeak() =
        runTest {
            val requests = AtomicInteger(0)
            val server = startServer(HTTP_OK) { _ -> requests.incrementAndGet() }

            val result =
                senderFor("http://$LOOPBACK:${server.address.port}$WEBHOOK_PATH")
                    .sendRide(RIDE_ID, PAYLOAD)

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertEquals(INVALID_CONFIGURATION_MESSAGE, result.errorMessage)
            assertFalse(result.errorMessage.orEmpty().contains(LOOPBACK))
            assertEquals(0, requests.get())
        }

    @Test
    fun given_configTimeoutExceeded_when_sendRide_then_failure() =
        runTest {
            val releaseHandler = CountDownLatch(1)
            val server =
                startServer(HTTP_OK) { _ ->
                    releaseHandler.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }

            val result = senderFor(webhookUrl(server), timeoutSeconds = 1).sendRide(RIDE_ID, PAYLOAD)
            releaseHandler.countDown()

            assertFalse(result.success)
            assertNull(result.statusCode)
            assertNotNull(result.errorMessage)
        }

    @Test
    fun given_timeoutBelowOneSecond_when_sendRide_then_clampedToMinimum() =
        runTest {
            val releaseHandler = CountDownLatch(1)
            val server =
                startServer(HTTP_OK) { _ ->
                    // A zero timeout would mean "infinite" in HttpURLConnection;
                    // this delay proves the 1 s floor is applied instead.
                    releaseHandler.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }

            val result = senderFor(webhookUrl(server), timeoutSeconds = 0).sendRide(RIDE_ID, PAYLOAD)
            releaseHandler.countDown()

            assertFalse(result.success)
            assertNull(result.statusCode)
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
    ): HttpsServer {
        val server = HttpsServer.create(InetSocketAddress(LOOPBACK, EPHEMERAL_PORT), 0)
        server.httpsConfigurator = HttpsConfigurator(TestTls.sslContext())
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
                } catch (ignored: IOException) {
                    // A timeout test may close the connection before the
                    // handler finishes; the assertion is on the client result.
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

    private fun senderFor(
        endpoint: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): HttpHermesSender =
        HttpHermesSender(
            config = HermesConfig(endpoint = endpoint, timeoutSeconds = timeoutSeconds),
            hmacSigner = HmacSha256Signer(FakeHmacKeyProvider(KEY)),
            nowSeconds = { TIMESTAMP },
        )

    private fun webhookUrl(server: HttpsServer): String = "https://$LOOPBACK:${server.address.port}$WEBHOOK_PATH"

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

    /** Loads the throwaway self-signed test keystore from test resources. */
    private object TestTls {
        private const val RESOURCE = "test-tls.p12"

        fun sslContext(): SSLContext {
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            val stream =
                checkNotNull(TestTls::class.java.classLoader?.getResourceAsStream(RESOURCE)) {
                    "missing test keystore $RESOURCE"
                }
            stream.use { keyStore.load(it, PASSWORD) }
            val keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore, PASSWORD)
                }
            val trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore)
                }
            return SSLContext.getInstance(PROTOCOL).apply {
                init(keyManagers.keyManagers, trustManagers.trustManagers, null)
            }
        }

        private const val KEYSTORE_TYPE = "PKCS12"
        private const val PROTOCOL = "TLS"
        private val PASSWORD = "testpass".toCharArray()
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
        const val INVALID_CONFIGURATION_MESSAGE = "invalid endpoint configuration"
        const val SIGNATURE_COUNT = 2
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val RELEASE_TIMEOUT_SECONDS = 5L
        const val TIMESTAMP = 1_700_000_000L
        const val RIDE_ID = "ride-123"
        const val ALGORITHM = "HmacSHA256"
        val KEY = "test-secret".toByteArray(StandardCharsets.UTF_8)
        val PAYLOAD = """{"rideId":"ride-123","distanceMeters":1234.5}""".toByteArray(StandardCharsets.UTF_8)
        val ALTERNATE_PAYLOAD = """{"rideId":"ride-123","distanceMeters":9999.9}""".toByteArray(StandardCharsets.UTF_8)
    }
}
