package com.perch.cam.control

import com.perch.cam.util.MiniJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/** Exercises the real socket server end to end on a loopback port. */
class ControlServerTest {

    private val token = "test-token"
    private val backend = FakeBackend()
    private var now = 1_000L
    private lateinit var server: ControlServer
    private var port = 0

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = ControlServer(
            ControlApi(backend) { token },
            port,
            AuthThrottle(maxFailures = 3, windowMs = 60_000),
            clock = { now }
        )
        assertTrue(server.start())
    }

    @After
    fun tearDown() = server.stop()

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        auth: String? = "Bearer $token"
    ): Pair<Int, HttpURLConnection> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        auth?.let { conn.setRequestProperty("Authorization", it) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return conn.responseCode to conn
    }

    private fun HttpURLConnection.text() =
        (if (responseCode >= 400) errorStream else inputStream).bufferedReader().use { it.readText() }

    @Test
    fun servesStatusToAuthorizedClients() {
        val (code, conn) = request("GET", "/api/status")
        assertEquals(200, code)
        assertEquals("*", conn.getHeaderField("Access-Control-Allow-Origin"))
        val status = MiniJson.parseObject(conn.text())!!
        assertEquals("Test Phone", status["deviceName"])
    }

    @Test
    fun appliesActionsFromPostBodies() {
        val (code, _) = request("POST", "/api/flashlight", """{"on":true}""")
        assertEquals(200, code)
        assertTrue(backend.flashlight)
    }

    @Test
    fun unauthorizedResponsesStillCarryCorsHeadersSoBrowsersSeeThe401() {
        val (code, conn) = request("GET", "/api/status", auth = null)
        assertEquals(401, code)
        assertEquals("*", conn.getHeaderField("Access-Control-Allow-Origin"))
    }

    @Test
    fun answersCorsPreflightWithoutCredentials() {
        val (code, conn) = request("OPTIONS", "/api/flashlight", auth = null)
        assertEquals(204, code)
        assertTrue(conn.getHeaderField("Access-Control-Allow-Headers").contains("Authorization"))
        assertEquals("true", conn.getHeaderField("Access-Control-Allow-Private-Network"))
    }

    @Test
    fun locksOutAnAddressAfterRepeatedBadTokens() {
        repeat(3) { assertEquals(401, request("GET", "/api/status", auth = "Bearer wrong").first) }
        // Even the right token is refused while locked out...
        assertEquals(429, request("GET", "/api/status").first)
        // ...and allowed again once the window has passed.
        now += 61_000
        assertEquals(200, request("GET", "/api/status").first)
    }

    @Test
    fun rejectsOversizedBodiesAndChunkedUploads() {
        Socket("127.0.0.1", port).use { s ->
            val big = "x".repeat(10_000)
            s.getOutputStream().write(
                ("POST /api/siren HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\n" +
                    "Content-Length: ${big.length}\r\n\r\n$big").toByteArray()
            )
            assertTrue(String(s.getInputStream().readBytes()).startsWith("HTTP/1.1 413"))
        }
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write(
                ("POST /api/siren HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n").toByteArray()
            )
            assertTrue(String(s.getInputStream().readBytes()).startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun survivesGarbageAndKeepsServing() {
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("\u0000\u0001 not http at all\r\n\r\n".toByteArray())
            val reply = String(s.getInputStream().readBytes())
            assertTrue(reply.startsWith("HTTP/1.1 400"))
        }
        assertEquals(200, request("GET", "/api/status").first)
    }

    @Test
    fun clientsDribblingBytesCannotMonopoliseTheServer() {
        // Reconfigure with a short total deadline so the test is quick.
        server.stop()
        server = ControlServer(ControlApi(backend) { token }, port, requestDeadlineMs = 800)
        assertTrue(server.start())

        // More slow clients than worker threads, each sending one header byte every 200 ms.
        val slow = List(6) {
            Socket("127.0.0.1", port).also { s ->
                Thread {
                    runCatching {
                        s.getOutputStream().apply { write("GET /api/status HTTP/1.1\r\nX".toByteArray()); flush() }
                        repeat(50) { Thread.sleep(200); s.getOutputStream().write('a'.code); s.getOutputStream().flush() }
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        try {
            Thread.sleep(300)
            val started = System.currentTimeMillis()
            assertEquals("a normal request must still be served promptly", 200, request("GET", "/api/status").first)
            assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 3_000)

            // The dribblers are cut off with 408 once the deadline passes.
            val reply = slow[0].also { it.soTimeout = 5_000 }.getInputStream().readBytes().decodeToString()
            assertTrue("expected 408, got: ${reply.take(40)}", reply.startsWith("HTTP/1.1 408"))
        } finally {
            slow.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun servesSeveralClientsConcurrently() {
        val results = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val threads = List(4) {
            Thread { results.add(request("GET", "/api/status").first) }.also { it.start() }
        }
        threads.forEach { it.join(5_000) }
        assertEquals(listOf(200, 200, 200, 200), results.toList())
    }

    @Test
    fun stopsListening() {
        server.stop()
        assertNotNull(runCatching { Socket("127.0.0.1", port).close() }.exceptionOrNull())
    }
}
