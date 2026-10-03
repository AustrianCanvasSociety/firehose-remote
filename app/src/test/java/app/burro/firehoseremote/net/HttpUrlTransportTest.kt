package app.burro.firehoseremote.net

import app.burro.firehoseremote.protocol.TransportTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * The real transport against a real socket on the loopback.
 *
 * A fake can only record the limit a caller asked for. These show the limit
 * reaching the connection — the half a fake cannot show, and the half that
 * decides whether a slow wake is heard at all.
 */
class HttpUrlTransportTest {

    /**
     * A reply slower than the transport's configured limit times out — which is
     * what makes the second half mean anything — and the same reply arrives when
     * the request carries a longer limit of its own.
     */
    @Test
    fun aPerRequestLimitOutlastsTheConfiguredOne() {
        SlowServer(replyAfterMs = 900).use { server ->
            val transport = HttpUrlTransport(configuredReadTimeoutMs = 300, configuredConnectTimeoutMs = 300)

            try {
                transport.request("POST", server.url)
                fail("a 900 ms reply must time out under a 300 ms limit")
            } catch (e: TransportTimeout) {
                // The configured limit applies when the request names none.
            }

            assertEquals(201, transport.request("POST", server.url, timeoutMs = 3000).status)
        }
    }

    /** A timeout names the limit that applied, not the transport's configured one. */
    @Test
    fun aTimeoutNamesTheLimitThatApplied() {
        SlowServer(replyAfterMs = 900).use { server ->
            val transport = HttpUrlTransport(configuredReadTimeoutMs = 300, configuredConnectTimeoutMs = 300)

            try {
                transport.request("POST", server.url, timeoutMs = 450)
                fail("a 900 ms reply must time out under a 450 ms limit")
            } catch (e: TransportTimeout) {
                assertTrue(e.message!!, e.message!!.contains("450ms"))
            }
        }
    }

    /**
     * Each request logs one line — method, URL, outcome, elapsed — and nothing
     * from its headers, which is where the pairing token travels.
     */
    @Test
    fun aRequestLogsOneLineAndNeverItsHeaders() {
        SlowServer(replyAfterMs = 0).use { server ->
            val lines = mutableListOf<String>()
            val transport = HttpUrlTransport(log = { lines += it })

            transport.request("POST", server.url, headers = mapOf("X-Client-Token" to "SECRET-TOKEN"))

            assertEquals(lines.toString(), 1, lines.size)
            assertTrue(lines[0], lines[0].startsWith("POST ${server.url} -> 201 in "))
            assertFalse(lines[0], lines[0].contains("SECRET-TOKEN"))
        }
    }

    /** A timeout is logged as one, so a slow wake reads as slow rather than missing. */
    @Test
    fun aTimeoutIsLoggedAsATimeout() {
        SlowServer(replyAfterMs = 900).use { server ->
            val lines = mutableListOf<String>()
            val transport = HttpUrlTransport(
                configuredReadTimeoutMs = 300,
                configuredConnectTimeoutMs = 300,
                log = { lines += it }
            )

            try {
                transport.request("POST", server.url)
                fail("a 900 ms reply must time out under a 300 ms limit")
            } catch (e: TransportTimeout) {
                assertTrue(lines.toString(), lines.single().startsWith("POST ${server.url} -> timed out in "))
            }
        }
    }

    /**
     * Answers every connection with `201` after [replyAfterMs], each on its own
     * thread so a connection the client abandoned never holds up the next.
     */
    private class SlowServer(private val replyAfterMs: Long) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/apps/FireTVRemote"

        init {
            thread(isDaemon = true) {
                while (true) {
                    val conn = try { socket.accept() } catch (e: SocketException) { break }
                    thread(isDaemon = true) {
                        try {
                            conn.use {
                                val reader = it.getInputStream().bufferedReader()
                                while (!reader.readLine().isNullOrEmpty()) { }
                                Thread.sleep(replyAfterMs)
                                it.getOutputStream().write(
                                    "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                                )
                            }
                        } catch (e: IOException) {
                            // The client gave up first — the case half these tests are about.
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }
}
