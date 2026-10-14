package com.perch.cam.control

import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A deliberately tiny HTTP/1.1 server for the control API: one request per connection, JSON only,
 * hard limits on header and body size, short socket timeouts and a bounded worker pool, so a
 * misbehaving client on the LAN cannot tie the phone up. Every request except CORS preflights
 * needs the bearer token (checked by [ControlApi]).
 */
class ControlServer(
    private val api: ControlApi,
    private val port: Int,
    private val throttle: AuthThrottle = AuthThrottle(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Total time a client gets to send one complete request. */
    private val requestDeadlineMs: Long = 10_000L
) {
    private val tag = "Perch:ControlServer"

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ThreadPoolExecutor? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Starts listening; returns false if the port is unavailable. */
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            // core == max: a ThreadPoolExecutor only grows past its core size when the queue is
            // full, so core 0 would serve everyone through a single worker thread.
            workers = ThreadPoolExecutor(
                MAX_WORKERS, MAX_WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUED)
            ) { r -> Thread(r, "perch-control-worker").apply { isDaemon = true } }.apply {
                allowCoreThreadTimeOut(true)
            }
            acceptThread = Thread({ acceptLoop(socket) }, "perch-control-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(tag, "Control API listening on port $port")
            true
        } catch (e: IOException) {
            Log.e(tag, "Could not start control API on port $port", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
        acceptThread = null
        Log.i(tag, "Control API stopped")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break // closed by stop()
            }
            try {
                workers?.execute { handleConnection(client) } ?: client.close()
            } catch (e: RejectedExecutionException) {
                client.close() // overloaded: shed load
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = SOCKET_TIMEOUT_MS
                val peer = s.inetAddress?.hostAddress ?: "unknown"
                val response = respond(s.getInputStream(), peer)
                s.getOutputStream().apply {
                    write(response)
                    flush()
                }
            } catch (e: IOException) {
                Log.d(tag, "Connection dropped: ${e.message}")
            } catch (e: Exception) {
                Log.e(tag, "Unexpected error handling request", e)
            }
        }
    }

    private fun respond(input: InputStream, peer: String): ByteArray {
        val request = try {
            readRequest(input, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestDeadlineMs))
        } catch (e: BadRequest) {
            return http(e.status, """{"error":"${e.message}"}""")
        }

        if (request.method == "OPTIONS") return preflight()

        val now = clock()
        if (throttle.isBlocked(peer, now)) {
            return http(429, """{"error":"Too many failed attempts, try again in a minute"}""")
        }

        val authorized = api.isAuthorized(request.headers)
        if (!authorized) {
            throttle.recordFailure(peer, now)
        } else {
            throttle.recordSuccess(peer)
        }

        val result = runBlocking {
            try {
                api.handle(request)
            } catch (e: Exception) {
                Log.e(tag, "Handler failed for ${request.method} ${request.path}", e)
                ControlApi.Response(500, """{"error":"Internal error"}""")
            }
        }
        return http(result.status, result.body)
    }

    private class BadRequest(val status: Int, message: String) : Exception(message)

    private fun readRequest(input: InputStream, deadlineNs: Long): ControlApi.Request {
        val head = readHead(input, deadlineNs)
        val lines = head.split("\r\n")
        val parts = lines.firstOrNull()?.split(" ") ?: throw BadRequest(400, "Malformed request")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw BadRequest(400, "Malformed request line")

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) throw BadRequest(400, "Malformed header")
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw BadRequest(400, "Chunked bodies are not supported")

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) throw BadRequest(413, "Body too large")
        val body = if (length > 0) String(readExactly(input, length, deadlineNs), Charsets.UTF_8) else ""

        val target = parts[1]
        val queryStart = target.indexOf('?')
        val path = if (queryStart >= 0) target.substring(0, queryStart) else target
        val query = if (queryStart >= 0) parseQuery(target.substring(queryStart + 1)) else emptyMap()

        return ControlApi.Request(parts[0].uppercase(), path, query, headers, body)
    }

    /** Reads up to and including the blank line that ends the headers, byte by byte (bounded). */
    private fun readHead(input: InputStream, deadlineNs: Long): String {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (true) {
            checkDeadline(deadlineNs)
            val b = try {
                input.read()
            } catch (e: java.net.SocketTimeoutException) {
                throw BadRequest(408, "Request timed out")
            }
            if (b < 0) throw BadRequest(400, "Unexpected end of request")
            buf.write(b)
            if (buf.size() > MAX_HEAD_BYTES) throw BadRequest(431, "Headers too large")
            matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
            if (matched == terminator.size) break
        }
        return String(buf.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
    }

    private fun readExactly(input: InputStream, length: Int, deadlineNs: Long): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            checkDeadline(deadlineNs)
            val n = try {
                input.read(data, read, length - read)
            } catch (e: java.net.SocketTimeoutException) {
                throw BadRequest(408, "Request timed out")
            }
            if (n < 0) throw BadRequest(400, "Unexpected end of body")
            read += n
        }
        return data
    }

    /** The socket timeout is per read, so a client dribbling one byte at a time needs a total limit. */
    private fun checkDeadline(deadlineNs: Long) {
        if (System.nanoTime() > deadlineNs) throw BadRequest(408, "Request timed out")
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    private fun preflight(): ByteArray = build(
        204, "No Content", "",
        extra = listOf(
            "Access-Control-Allow-Methods: GET, POST, OPTIONS",
            "Access-Control-Allow-Headers: Authorization, Content-Type",
            // Chrome requires this for a public/https page calling a private LAN address.
            "Access-Control-Allow-Private-Network: true",
            "Access-Control-Max-Age: 600"
        )
    )

    private fun http(status: Int, json: String): ByteArray = build(status, reason(status), json)

    private fun build(status: Int, reason: String, body: String, extra: List<String> = emptyList()): ByteArray {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            // The bearer token, not the origin, is what protects the API.
            append("Access-Control-Allow-Origin: *\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return head + payload
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        408 -> "Request Timeout"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        else -> "Error"
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 5_000
        const val MAX_HEAD_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 4 * 1024
        const val MAX_WORKERS = 4
        const val MAX_QUEUED = 8
    }
}
