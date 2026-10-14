package com.perch.cam.streaming

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal WHIP (WebRTC-HTTP ingestion protocol) client: publish an SDP offer over HTTP, receive
 * the SDP answer, and later delete the session resource. Blocking; call from an IO thread.
 */
class WhipClient(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000
) {
    class WhipException(message: String, val statusCode: Int? = null) : IOException(message)

    data class Session(val answerSdp: String, val resourceUrl: String?)

    fun publish(whipUrl: String, offerSdp: String, bearerToken: String?): Session {
        val url = parseHttpUrl(whipUrl)
        val conn = open(url, "POST", bearerToken)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/sdp")
            conn.outputStream.use { it.write(offerSdp.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_CREATED && status != HttpURLConnection.HTTP_OK) {
                val detail = readBody(conn, error = true).take(200)
                throw WhipException("WHIP server answered HTTP $status${if (detail.isNotBlank()) ": $detail" else ""}", status)
            }

            val answer = readBody(conn, error = false)
            if (answer.isBlank()) throw WhipException("WHIP server returned an empty SDP answer", status)

            val location = conn.getHeaderField("Location")
            val resource = location?.let { URL(url, it).toString() }
            return Session(answer, resource)
        } finally {
            conn.disconnect()
        }
    }

    /** Ends the session on the server. Failures are ignored by callers: the server also times out. */
    fun terminate(resourceUrl: String, bearerToken: String?) {
        val conn = open(parseHttpUrl(resourceUrl), "DELETE", bearerToken)
        try {
            conn.responseCode // sends the request
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: URL, method: String, bearerToken: String?): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false // a redirected POST would silently become a GET
        if (!bearerToken.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $bearerToken")
        return conn
    }

    private fun readBody(conn: HttpURLConnection, error: Boolean): String {
        val stream = (if (error) conn.errorStream else conn.inputStream) ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        fun parseHttpUrl(raw: String): URL {
            val url = try {
                URL(raw.trim())
            } catch (e: Exception) {
                throw WhipException("Invalid stream URL: $raw")
            }
            if (url.protocol != "http" && url.protocol != "https") {
                throw WhipException("Stream URL must start with http:// or https://")
            }
            return url
        }
    }
}
