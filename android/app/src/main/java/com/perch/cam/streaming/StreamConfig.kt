package com.perch.cam.streaming

data class StreamConfig(
    /** WHIP endpoint of the media server, e.g. `http://192.168.1.100:8889/perch/whip`. */
    val whipUrl: String,
    /** Optional bearer token if the media server requires publish authentication. */
    val bearerToken: String = "",
    val maxWidth: Int = 1280,
    val maxHeight: Int = 720,
    val fps: Int = DEFAULT_FPS,
    val bitrateKbps: Int = 2000
) {
    companion object {
        const val DEFAULT_FPS = 24
    }
}
