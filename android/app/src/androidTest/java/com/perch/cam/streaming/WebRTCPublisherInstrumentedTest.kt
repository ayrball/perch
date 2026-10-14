package com.perch.cam.streaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** A WHIP endpoint whose behaviour each test scripts. */
private class ScriptedWhipServer {
    class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val socket = ServerSocket(0)
    val port get() = socket.localPort
    val requests = CopyOnWriteArrayList<Seen>()

    @Volatile var status = 500
    @Volatile var location: String? = null
    @Volatile var answerBody = "boom"

    init {
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                Thread { client.use { runCatching { handle(it) } }.let { } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

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
        requests.add(Seen(method, path, headers, body))

        val isPost = method == "POST"
        val reply = if (isPost) answerBody else ""
        val loc = if (isPost && location != null) "Location: $location\r\n" else ""
        client.getOutputStream().write(
            ("HTTP/1.1 ${if (isPost) status else 200} X\r\n${loc}Content-Length: ${reply.length}\r\nConnection: close\r\n\r\n$reply")
                .toByteArray()
        )
    }

    fun posts() = requests.filter { it.method == "POST" }
    fun deletes() = requests.filter { it.method == "DELETE" }
    fun stop() = socket.close()
}

/**
 * Drives the real WebRTC stack (native libs, ICE gathering, offer creation) against a fake
 * server: what is sent, how failures are retried, and that every session is cleaned up.
 */
@RunWith(AndroidJUnit4::class)
class WebRTCPublisherInstrumentedTest {

    private lateinit var scope: CoroutineScope
    private lateinit var server: ScriptedWhipServer
    private lateinit var publisher: WebRTCPublisher

    private val url get() = "http://127.0.0.1:${server.port}/perch/whip"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = ScriptedWhipServer()
        publisher = WebRTCPublisher(InstrumentationRegistry.getInstrumentation().targetContext, scope)
    }

    @After
    fun tearDown() {
        publisher.release()
        server.stop()
        scope.cancel()
    }

    private fun await(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $what")
    }

    @Test
    fun sendsAWellFormedSendOnlyVideoOffer() {
        publisher.start(StreamConfig(whipUrl = url, bearerToken = "secret"))

        await(20_000, "first WHIP POST") { server.posts().isNotEmpty() }
        val post = server.posts().first()
        assertEquals("/perch/whip", post.path)
        assertEquals("application/sdp", post.headers["content-type"])
        assertEquals("Bearer secret", post.headers["authorization"])
        assertTrue("offer has a video section", post.body.contains("m=video"))
        assertTrue("offer is send-only", post.body.contains("a=sendonly"))
        assertTrue("offer has a DTLS fingerprint", post.body.contains("a=fingerprint:"))
        assertTrue("offer has ICE credentials", post.body.contains("a=ice-ufrag:"))
        assertTrue("LAN host candidates were gathered before sending", post.body.contains("a=candidate:"))
        assertTrue("no audio is published", !post.body.contains("m=audio"))
    }

    @Test
    fun aServerErrorIsReportedAndRetriedWithBackoffUntilStopped() {
        server.status = 500
        publisher.start(StreamConfig(whipUrl = url))

        await(20_000, "first attempt") { server.posts().size >= 1 }
        await(5_000, "RECONNECTING status") { publisher.status.value.state == StreamState.RECONNECTING }
        assertTrue(
            "detail should mention the HTTP status: ${publisher.status.value.detail}",
            publisher.status.value.detail?.contains("500") == true
        )
        await(10_000, "automatic retry") { server.posts().size >= 2 }

        publisher.stop()
        assertEquals(StreamState.IDLE, publisher.status.value.state)
        val count = server.posts().size
        Thread.sleep(3_000)
        assertEquals("no further attempts after stop()", count, server.posts().size)
    }

    @Test
    fun anUnusableAnswerEndsTheSessionOnTheServerAndDoesNotCrash() {
        server.status = 201
        server.location = "/perch/whip/session-1"
        server.answerBody = "v=0\r\nthis is not a valid answer"
        publisher.start(StreamConfig(whipUrl = url))

        await(20_000, "client cleans up the half-open session") { server.deletes().isNotEmpty() }
        assertEquals("/perch/whip/session-1", server.deletes().first().path)
        await(5_000, "RECONNECTING status") { publisher.status.value.state == StreamState.RECONNECTING }
        assertNotNull(publisher.status.value.detail)
    }

    @Test
    fun anUnreachableServerKeepsRetryingInsteadOfFailingPermanently() {
        val dead = ServerSocket(0).use { it.localPort }
        publisher.start(StreamConfig(whipUrl = "http://127.0.0.1:$dead/perch/whip"))

        await(20_000, "RECONNECTING after a refused connection") {
            publisher.status.value.state == StreamState.RECONNECTING
        }
    }

    @Test
    fun aMalformedUrlIsReportedNotThrown() {
        publisher.start(StreamConfig(whipUrl = "ftp://nonsense"))

        await(20_000, "RECONNECTING with a clear reason") {
            publisher.status.value.state == StreamState.RECONNECTING &&
                publisher.status.value.detail?.contains("http", ignoreCase = true) == true
        }
    }

    @Test
    fun rapidStartStopCyclesDoNotCrashOrLeakThreads() {
        server.status = 500
        publisher.start(StreamConfig(whipUrl = url))
        await(20_000, "warm-up attempt") { server.posts().isNotEmpty() }
        publisher.stop()
        Thread.sleep(1_500)
        val baseline = Thread.getAllStackTraces().size

        repeat(8) {
            publisher.start(StreamConfig(whipUrl = url))
            Thread.sleep(400)
            publisher.stop()
        }
        Thread.sleep(4_000) // let cancelled sessions finish disposing
        val after = Thread.getAllStackTraces().size

        assertEquals(StreamState.IDLE, publisher.status.value.state)
        assertTrue("threads grew from $baseline to $after across 8 start/stop cycles", after - baseline < 12)
    }

    @Test
    fun restartingWithANewConfigReplacesTheOldSession() {
        server.status = 500
        publisher.start(StreamConfig(whipUrl = url, bearerToken = "one"))
        await(20_000, "first attempt") { server.posts().isNotEmpty() }

        publisher.start(StreamConfig(whipUrl = url, bearerToken = "two"))
        await(20_000, "attempt with the new token") { server.posts().any { it.headers["authorization"] == "Bearer two" } }
    }
}
