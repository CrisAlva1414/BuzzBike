package com.buzzbike.app.sync

import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.buzzbike.app.domain.sync.OutboxItem
import com.buzzbike.app.domain.sync.OutboxProcessor
import com.buzzbike.app.domain.sync.OutboxProcessorConfig
import com.buzzbike.app.domain.sync.OutboxProcessorDependencies
import com.buzzbike.app.domain.sync.OutboxStore
import com.buzzbike.app.domain.sync.ProcessOutcome
import com.buzzbike.app.domain.sync.RidePayloadBuilder
import com.buzzbike.app.domain.sync.RidePayloadJson
import com.buzzbike.app.domain.sync.RideSyncInput
import com.buzzbike.app.domain.sync.SyncConsentChecker
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * End-to-end JVM test of the optional Hermes sync over a real local server.
 *
 * Production requires HTTPS, so the server is an ephemeral
 * `com.sun.net.httpserver.HttpsServer` (the TLS subclass of `HttpServer`)
 * bound to loopback with the throwaway test certificate from
 * `src/test/resources/test-tls.p12`. No Android classes are involved:
 * [HttpHermesSender], [HmacSha256Signer] and [HttpConnectivityChecker] are the
 * same implementations wired in production.
 *
 * The test drives the full outbox path ([OutboxProcessor] + real transport +
 * real HMAC signer) with a real data-minimized ride payload, and pins:
 * - the happy path: signed `POST /webhooks/ride`, JSON content type and a
 *   signature the receiver can verify against the exact body;
 * - the authenticated health probe on a dedicated route;
 * - `401` and `429` are returned to the processor as terminal/transient
 *   outcomes **without** an in-process retry loop (one request, no attempt
 *   consumed).
 */
class HermesSyncE2ETest {
    private val servers = mutableListOf<HttpsServer>()
    private val executors = mutableListOf<ExecutorService>()
    private val healthRequests = CopyOnWriteArrayList<CapturedRequest>()
    private val webhookRequests = CopyOnWriteArrayList<CapturedRequest>()
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
    fun given_healthyReceiver_when_process_then_sendsSignedRidePayloadOnce() =
        runTest {
            val server = startServer(HTTP_OK)
            val payload = ridePayload()
            val store = RecordingOutboxStore(listOf(outboxItem(payload)))
            val processor = processor(store, server)

            val outcome = processor.process(healthUrl(server), hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.Sent(1), outcome)
            assertEquals(listOf(OUTBOX_ID), store.sentIds)
            assertTrue(store.failedMarks.isEmpty())

            assertEquals(1, healthRequests.size)
            val health = healthRequests.single()
            assertEquals(HTTP_GET, health.method)
            assertEquals(HEALTH_PATH, health.path)
            assertTrue(isValidSignature(health.timestamp, EMPTY_BODY, health.signature))

            val request = webhookRequests.single()
            assertEquals(HTTP_POST, request.method)
            assertEquals(WEBHOOK_PATH, request.path)
            assertEquals(CONTENT_TYPE_JSON, request.contentType)
            assertEquals(TIMESTAMP.toString(), request.timestamp)
            assertEquals(expectedSignature(TIMESTAMP, payload), request.signature)
            assertTrue(isValidSignature(request.timestamp, request.body, request.signature))
            assertEquals(payload.toList(), request.body.toList())
        }

    @Test
    fun given_receiverRejectsSignature_when_process_then_stopsAfterOneRequest() =
        runTest {
            val server = startServer(HTTP_UNAUTHORIZED)
            val payload = ridePayload()
            val store = RecordingOutboxStore(listOf(outboxItem(payload)))
            val processor = processor(store, server)

            val outcome = processor.process(healthUrl(server), hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.ConfigurationError, outcome)
            assertEquals(1, webhookRequests.size)
            assertTrue(store.sentIds.isEmpty())
            assertTrue(store.failedMarks.isEmpty())
            assertEquals(listOf(RIDE_ID), store.pendingRideIds())
        }

    @Test
    fun given_receiverRateLimits_when_process_then_stopsAfterOneRequest() =
        runTest {
            val server = startServer(HTTP_TOO_MANY_REQUESTS)
            val payload = ridePayload()
            val store = RecordingOutboxStore(listOf(outboxItem(payload)))
            val processor = processor(store, server)

            val outcome = processor.process(healthUrl(server), hmacKeyConfigured = true)

            assertEquals(ProcessOutcome.BackendUnavailable, outcome)
            assertEquals(1, webhookRequests.size)
            assertTrue(store.sentIds.isEmpty())
            assertTrue(store.failedMarks.isEmpty())
            assertEquals(listOf(RIDE_ID), store.pendingRideIds())
        }

    private fun startServer(webhookStatus: Int): HttpsServer {
        val server = HttpsServer.create(InetSocketAddress(LOOPBACK, EPHEMERAL_PORT), 0)
        server.httpsConfigurator = HttpsConfigurator(TestTls.sslContext())
        val executor =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable).apply { isDaemon = true }
            }
        server.executor = executor
        server.createContext(HEALTH_PATH) { exchange ->
            handle(exchange, HTTP_OK, healthRequests)
        }
        server.createContext(WEBHOOK_PATH) { exchange ->
            handle(exchange, webhookStatus, webhookRequests)
        }
        server.start()
        servers += server
        executors += executor
        return server
    }

    private fun handle(
        exchange: HttpExchange,
        status: Int,
        sink: MutableList<CapturedRequest>,
    ) {
        try {
            val body = exchange.requestBody.readBytes()
            sink +=
                CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    contentType = exchange.requestHeaders.getFirst(HEADER_CONTENT_TYPE),
                    timestamp = exchange.requestHeaders.getFirst(HEADER_TIMESTAMP),
                    signature = exchange.requestHeaders.getFirst(HEADER_SIGNATURE),
                    body = body,
                )
            exchange.sendResponseHeaders(status, NO_RESPONSE_BODY)
        } catch (ignored: IOException) {
            // The client may close first when it considers the exchange done.
        } finally {
            exchange.close()
        }
    }

    private fun processor(
        store: OutboxStore,
        server: HttpsServer,
    ): OutboxProcessor {
        val signer = HmacSha256Signer(FakeHmacKeyProvider(KEY))
        val config =
            HermesConfig(
                endpoint = url(server, WEBHOOK_PATH),
                healthCheckUrl = url(server, HEALTH_PATH),
            )
        return OutboxProcessor(
            OutboxProcessorDependencies(
                store = store,
                connectivity = HttpConnectivityChecker(signer, nowSeconds = { TIMESTAMP }),
                sender = HttpHermesSender(config, signer, nowSeconds = { TIMESTAMP }),
                consent = AllowedConsentChecker,
                clock = { NOW_MILLIS },
                config = OutboxProcessorConfig(batchSize = BATCH_SIZE, maxBatches = MAX_BATCHES),
            ),
        )
    }

    private fun url(
        server: HttpsServer,
        path: String,
    ): String = "https://$LOOPBACK:${server.address.port}$path"

    private fun healthUrl(server: HttpsServer): String = url(server, HEALTH_PATH)

    private fun outboxItem(payload: ByteArray): OutboxItem =
        OutboxItem(
            id = OUTBOX_ID,
            rideId = RIDE_ID,
            payload = payload,
            attemptCount = 0,
            maxAttempts = MAX_ATTEMPTS,
        )

    /** Builds and encodes a real data-minimized ride payload. */
    private fun ridePayload(): ByteArray {
        val input =
            RideSyncInput(
                rideId = RIDE_ID,
                startTimeMillis = START_MILLIS,
                endTimeMillis = START_MILLIS + END_OFFSET_MILLIS,
                descriptors =
                    RideDescriptors(
                        distanceM = 1_000.0,
                        elapsedSeconds = 100,
                        movingSeconds = 90,
                        stoppedSeconds = 10,
                        avgMovingSpeedMps = 11.1,
                        avgSpeedMps = 10.0,
                        maxSpeedMps = 9.5,
                        stopCount = 1,
                        stopDurationsSeconds = listOf(10L),
                        acceptedPoints = 2,
                        rejectedPoints = 0,
                        gapCount = 0,
                        distanceIsLowerBound = false,
                        modelVersion = MODEL_VERSION,
                    ),
                elevationGainM = 12.5,
                elevationLossM = 4.0,
                caloriesKcal = 250.0,
                caloriesLowKcal = 175.0,
                caloriesHighKcal = 325.0,
                calorieModel = CALORIE_MODEL,
                maxSpeedMps = 9.5,
                points =
                    listOf(
                        TrackPoint(
                            timestampMonotonicNanos = 0L,
                            lat = BASE_LAT,
                            lon = BASE_LON,
                        ),
                        TrackPoint(
                            timestampMonotonicNanos = NANOS_PER_SECOND,
                            lat = BASE_LAT + LAT_STEP,
                            lon = BASE_LON + LON_STEP,
                        ),
                    ),
            )
        return RidePayloadJson.encode(RidePayloadBuilder.build(input))
    }

    private fun isValidSignature(
        timestamp: String?,
        body: ByteArray,
        signature: String?,
    ): Boolean {
        val ts = timestamp?.toLongOrNull()
        val provided = signature.orEmpty()
        // Constant-time comparison is exercised by HmacSha256Signer.verify.
        return ts != null &&
            provided.isNotBlank() &&
            HmacSha256Signer(FakeHmacKeyProvider(KEY)).verify(ts, body, provided) &&
            expectedSignature(ts, body) == provided
    }

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

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val contentType: String?,
        val timestamp: String?,
        val signature: String?,
        val body: ByteArray,
    )

    private object AllowedConsentChecker : SyncConsentChecker {
        override fun currentEpoch(): Long = 0

        override suspend fun isSyncAllowed(epochAtStart: Long): Boolean = true
    }

    private class FakeHmacKeyProvider(
        private val key: ByteArray,
    ) : HmacKeyProvider {
        override fun getOrCreateKey(): SecretKey = SecretKeySpec(key, ALGORITHM)
    }

    private class RecordingOutboxStore(
        items: List<OutboxItem>,
    ) : OutboxStore {
        private val remaining = items.associateByTo(LinkedHashMap(), OutboxItem::id)
        val sentIds: MutableList<String> = mutableListOf()
        val failedMarks: MutableList<FailedMark> = mutableListOf()

        fun pendingRideIds(): List<String> = remaining.values.map(OutboxItem::rideId)

        override suspend fun getPending(
            nowMillis: Long,
            limit: Int,
        ): List<OutboxItem> = remaining.values.take(limit)

        override suspend fun markSent(
            id: String,
            nowMillis: Long,
        ) {
            sentIds += id
            remaining.remove(id)
        }

        override suspend fun markFailed(
            id: String,
            error: String,
            nowMillis: Long,
            nextAttemptAtMillis: Long,
        ) {
            failedMarks += FailedMark(id, error, nowMillis, nextAttemptAtMillis)
        }

        override suspend fun deleteSent() = Unit

        override suspend fun deleteFailedOlderThan(cutoffMillis: Long) = Unit

        override suspend fun deleteAll() {
            remaining.clear()
        }
    }

    private data class FailedMark(
        val id: String,
        val error: String,
        val nowMillis: Long,
        val nextAttemptAtMillis: Long,
    )

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
        const val HEALTH_PATH = "/health"
        const val WEBHOOK_PATH = "/webhooks/ride"
        const val NO_RESPONSE_BODY = -1L
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_GET = "GET"
        const val HTTP_POST = "POST"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_TIMESTAMP = "X-Webhook-Timestamp"
        const val HEADER_SIGNATURE = "X-Webhook-Signature-V2"
        const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        const val ALGORITHM = "HmacSHA256"
        const val TIMESTAMP = 1_700_000_000L
        const val NOW_MILLIS = 1_000_000L
        const val RIDE_ID = "ride-e2e-1"
        const val OUTBOX_ID = "outbox-e2e-1"
        const val MAX_ATTEMPTS = 5
        const val BATCH_SIZE = 10
        const val MAX_BATCHES = 10
        const val START_MILLIS = 1_700_000_000_000L
        const val END_OFFSET_MILLIS = 90_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MODEL_VERSION = "e2e"
        const val CALORIE_MODEL = "COMPENDIUM_VELOCITY"
        const val BASE_LAT = -33.45
        const val BASE_LON = -70.66
        const val LAT_STEP = 0.001
        const val LON_STEP = 0.001
        val KEY = "e2e-test-secret".toByteArray(StandardCharsets.UTF_8)
        val EMPTY_BODY = ByteArray(0)
    }
}
