package com.perch.cam.streaming

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/** Just enough HTTP to stand in for a MediaMTX WHIP endpoint. */
private class FakeWhipServer {
    class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort
    val requests = mutableListOf<Seen>()

    var status = 201
    var location: String? = "/perch/whip/session-1"

    private val thread = Thread {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            client.use { handle(it) }
        }
    }.apply { isDaemon = true; start() }

    private fun handle(client: Socket) {
        val input = client.getInputStream()
        val head = ByteArrayOutputStream()
        while (!head.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
        }
        val lines = head.toString(Charsets.ISO_8859_1).trim().split("\r\n")
        val (method, path) = lines[0].split(" ")
        val headers = lines.drop(1).associate { it.substringBefore(":").lowercase() to it.substringAfter(":").trim() }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = if (length > 0) String(input.readNBytes(length)) else ""
        synchronized(requests) { requests.add(Seen(method, path, headers, body)) }

        val reply = if (method == "POST") (if (status in 200..299) "v=0\r\nanswer" else "nope") else ""
        val extra = if (method == "POST" && location != null) "Location: $location\r\n" else ""
        client.getOutputStream().write(
            ("HTTP/1.1 ${if (method == "POST") status else 200} X\r\n${extra}Content-Length: ${reply.length}\r\n" +
                "Connection: close\r\n\r\n$reply").toByteArray()
        )
    }

    fun last(method: String): Seen = synchronized(requests) { requests.last { it.method == method } }
    fun stop() = socket.close()
}

class WhipClientTest {

    private lateinit var server: FakeWhipServer
    private val base get() = "http://127.0.0.1:${server.port}"

    @Before
    fun setUp() {
        server = FakeWhipServer()
    }

    @After
    fun tearDown() = server.stop()

    @Test
    fun publishesOfferAndReturnsAnswerAndAbsoluteSessionUrl() {
        val session = WhipClient().publish("$base/perch/whip", "v=0\r\noffer", bearerToken = "tok")

        val seen = server.last("POST")
        assertEquals("/perch/whip", seen.path)
        assertEquals("v=0\r\noffer", seen.body)
        assertEquals("application/sdp", seen.headers["content-type"])
        assertEquals("Bearer tok", seen.headers["authorization"])
        assertEquals("v=0\r\nanswer", session.answerSdp)
        assertEquals("$base/perch/whip/session-1", session.resourceUrl)
    }

    @Test
    fun omitsAuthorizationWhenNoTokenIsConfigured() {
        WhipClient().publish("$base/perch/whip", "offer", bearerToken = "")
        assertNull(server.last("POST").headers["authorization"])
    }

    @Test
    fun missingLocationMeansNoResourceToDelete() {
        server.location = null
        assertNull(WhipClient().publish("$base/perch/whip", "offer", null).resourceUrl)
    }

    @Test
    fun serverErrorsSurfaceStatusAndMessage() {
        server.status = 404
        val e = runCatching { WhipClient().publish("$base/perch/whip", "offer", null) }.exceptionOrNull()
        assertTrue(e is WhipClient.WhipException)
        assertEquals(404, (e as WhipClient.WhipException).statusCode)
        assertTrue(e.message!!.contains("404"))
    }

    @Test
    fun terminateDeletesTheSessionResource() {
        WhipClient().terminate("$base/perch/whip/session-1", "tok")
        val seen = server.last("DELETE")
        assertEquals("/perch/whip/session-1", seen.path)
        assertEquals("Bearer tok", seen.headers["authorization"])
    }

    @Test
    fun rejectsNonHttpUrls() {
        listOf("rtsp://x/y", "not a url", "").forEach {
            assertTrue("should reject '$it'", runCatching { WhipClient.parseHttpUrl(it) }.isFailure)
        }
    }

    @Test
    fun unreachableServerIsAnIoError() {
        val client = WhipClient(connectTimeoutMs = 300, readTimeoutMs = 300)
        val url = "$base/perch/whip"
        server.stop()
        assertTrue(runCatching { client.publish(url, "offer", null) }.isFailure)
    }
}
