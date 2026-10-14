package com.perch.cam.streaming

/**
 * Caps the outgoing frame rate to an average of [fps].
 *
 * Camera frames arrive on a fixed clock (e.g. every 33.3 ms), so a naive "at least 1/fps since
 * the last frame" test rounds the rate to a divisor of the camera rate (24 fps from a 30 fps
 * camera would become 15). Instead frames are accepted against a schedule that advances by
 * exactly one interval per accepted frame, which averages out to the requested rate.
 */
class FrameRateLimiter(fps: Int = 24) {

    @Volatile
    private var intervalNs = intervalFor(fps)
    private var nextDueNs = Long.MIN_VALUE

    fun setFps(fps: Int) {
        intervalNs = intervalFor(fps)
    }

    @Synchronized
    fun accept(nowNs: Long): Boolean {
        val interval = intervalNs
        if (nextDueNs == Long.MIN_VALUE) {
            nextDueNs = nowNs + interval
            return true
        }
        if (nowNs < nextDueNs - JITTER_NS) return false

        nextDueNs += interval
        // After a stall, restart the schedule instead of bursting to catch up.
        if (nowNs - nextDueNs > interval) nextDueNs = nowNs + interval
        return true
    }

    private companion object {
        const val JITTER_NS = 2_000_000L // 2 ms of camera clock jitter

        fun intervalFor(fps: Int) = 1_000_000_000L / fps.coerceIn(1, 60)
    }
}
