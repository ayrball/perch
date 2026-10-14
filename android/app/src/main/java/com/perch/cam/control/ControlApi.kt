package com.perch.cam.control

import com.perch.cam.data.AlertEvent
import com.perch.cam.util.MiniJson
import java.security.MessageDigest

/** What the control API can do on the phone. Implemented by the recording service. */
interface ControlBackend {
    suspend fun status(): StatusSnapshot
    suspend fun events(limit: Int): List<AlertEvent>

    /** Sets the torch (toggles when [on] is null). Returns false if the active camera has no flash. */
    fun setFlashlight(on: Boolean?): Boolean
    fun startSiren(seconds: Int)
    fun stopSiren()
    fun flipCamera()
    fun setQuality(quality: String)
}

class ControlApi(
    private val backend: ControlBackend,
    private val tokenProvider: () -> String
) {
    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String> = emptyMap(),
        /** Header names lower-cased. */
        val headers: Map<String, String> = emptyMap(),
        val body: String = ""
    )

    data class Response(val status: Int, val body: String)

    companion object {
        val QUALITIES = setOf("720p", "1080p", "4K")
        const val DEFAULT_SIREN_SECONDS = 10
        const val MAX_SIREN_SECONDS = 60
        const val DEFAULT_EVENT_LIMIT = 50
        const val MAX_EVENT_LIMIT = 200
    }

    fun isAuthorized(headers: Map<String, String>): Boolean {
        val expected = tokenProvider()
        if (expected.isBlank()) return false
        val header = headers["authorization"] ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val presented = header.substring(7).trim()
        // Constant-time comparison so response timing does not leak the token.
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    suspend fun handle(request: Request): Response {
        if (!isAuthorized(request.headers)) return error(401, "Unauthorized")

        val route = request.path.trimEnd('/')
        val method = request.method.uppercase()

        return when (route) {
            "/api/status" -> allow(method, "GET") { ok(backend.status().toJsonMap()) }
            "/api/events" -> allow(method, "GET") {
                val limit = (request.query["limit"]?.toIntOrNull() ?: DEFAULT_EVENT_LIMIT)
                    .coerceIn(1, MAX_EVENT_LIMIT)
                ok(backend.events(limit).map { it.toJsonMap() })
            }
            "/api/flashlight" -> allow(method, "POST") {
                val body = parseBody(request.body) ?: return@allow error(400, "Body must be a JSON object")
                val on = body["on"]
                if (on != null && on !is Boolean) return@allow error(400, "\"on\" must be true or false")
                if (!backend.setFlashlight(on as Boolean?)) {
                    error(409, "The active camera has no flashlight")
                } else {
                    ok(backend.status().toJsonMap())
                }
            }
            "/api/siren" -> allow(method, "POST") {
                val body = parseBody(request.body) ?: return@allow error(400, "Body must be a JSON object")
                val seconds = when (val raw = body["seconds"]) {
                    null -> DEFAULT_SIREN_SECONDS
                    is Number -> raw.toInt()
                    else -> return@allow error(400, "\"seconds\" must be a number")
                }.coerceIn(1, MAX_SIREN_SECONDS)
                backend.startSiren(seconds)
                ok(backend.status().toJsonMap())
            }
            "/api/siren/stop" -> allow(method, "POST") {
                backend.stopSiren()
                ok(backend.status().toJsonMap())
            }
            "/api/camera/flip" -> allow(method, "POST") {
                backend.flipCamera()
                ok(backend.status().toJsonMap())
            }
            "/api/quality" -> allow(method, "POST") {
                val body = parseBody(request.body) ?: return@allow error(400, "Body must be a JSON object")
                val quality = body["quality"] as? String
                if (quality == null || quality !in QUALITIES) {
                    return@allow error(400, "\"quality\" must be one of ${QUALITIES.joinToString()}")
                }
                backend.setQuality(quality)
                ok(backend.status().toJsonMap())
            }
            else -> error(404, "Not found")
        }
    }

    private inline fun allow(method: String, expected: String, block: () -> Response): Response =
        if (method == expected) block() else error(405, "Use $expected for this endpoint")

    /** An empty body is an empty object; anything else must be a valid JSON object. */
    private fun parseBody(raw: String): Map<String, Any?>? =
        if (raw.isBlank()) emptyMap() else MiniJson.parseObject(raw)

    private fun ok(value: Any?) = Response(200, MiniJson.stringify(value))

    private fun error(status: Int, message: String) =
        Response(status, MiniJson.stringify(mapOf("error" to message)))
}
