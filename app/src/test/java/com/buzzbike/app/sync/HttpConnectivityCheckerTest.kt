package com.buzzbike.app.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JVM tests for [HttpConnectivityChecker] against a real, ephemeral
 * [HttpServer] on the loopback interface.
 *
 * They pin the product rule: **only HTTP 200 exactly counts as reachable**.
 * Every other status, malformed URL, refused connection or timeout must
 * report `false` without throwing.
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
    fun given_healthCheckReturns200_when_isReachable_then_true() =
        runTest {
            val server = startServer(HTTP_OK_CODE)

            assertTrue(HttpConnectivityChecker().isReachable(healthUrl(server)))
        }

    @Test
    fun given_healthCheckReturns204_when_isReachable_then_false() =
        runTest {
            val server = startServer(HTTP_NO_CONTENT_CODE)

            assertFalse(HttpConnectivityChecker().isReachable(healthUrl(server)))
        }

    @Test
    fun given_healthCheckReturns500_when_isReachable_then_false() =
        runTest {
            val server = startServer(HTTP_SERVER_ERROR_CODE)

            assertFalse(HttpConnectivityChecker().isReachable(healthUrl(server)))
        }

    @Test
    fun given_healthCheckReturns302_when_isReachable_then_false() =
        runTest {
            val server = startServer(HTTP_REDIRECT_CODE)

            assertFalse(HttpConnectivityChecker().isReachable(healthUrl(server)))
        }

    @Test
    fun given_malformedUrl_when_isReachable_then_false() =
        runTest {
            assertFalse(HttpConnectivityChecker().isReachable("not-a-url"))
        }

    @Test
    fun given_closedPort_when_isReachable_then_false() =
        runTest {
            val url = "http://$LOOPBACK:${findFreePort()}$HEALTH_PATH"

            assertFalse(HttpConnectivityChecker().isReachable(url))
        }

    @Test
    fun given_serverExceedsReadTimeout_when_isReachable_then_false() =
        runTest {
            val releaseHandler = CountDownLatch(1)
            val server =
                startServer { _ ->
                    releaseHandler.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            val checker =
                HttpConnectivityChecker(
                    connectTimeoutMillis = SHORT_TIMEOUT_MILLIS,
                    readTimeoutMillis = SHORT_TIMEOUT_MILLIS,
                )

            val reachable = checker.isReachable(healthUrl(server))
            releaseHandler.countDown()

            assertFalse(reachable)
        }

    private fun startServer(responseCode: Int): HttpServer =
        startServer { exchange ->
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

    private fun findFreePort(): Int = ServerSocket(EPHEMERAL_PORT).use { it.localPort }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val EPHEMERAL_PORT = 0
        const val HEALTH_PATH = "/health"
        const val NO_RESPONSE_BODY = -1L
        const val HTTP_OK_CODE = 200
        const val HTTP_NO_CONTENT_CODE = 204
        const val HTTP_REDIRECT_CODE = 302
        const val HTTP_SERVER_ERROR_CODE = 500
        const val SHORT_TIMEOUT_MILLIS = 200
        const val RELEASE_TIMEOUT_SECONDS = 5L
    }
}
