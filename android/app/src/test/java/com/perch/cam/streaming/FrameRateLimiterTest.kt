package com.perch.cam.streaming

import com.perch.cam.util.RestartBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameRateLimiterTest {

    /** Feeds [seconds] of a camera running at [sourceFps] (with timing jitter) and counts accepted frames. */
    private fun accepted(sourceFps: Int, targetFps: Int, seconds: Int = 10, jitterMs: Int = 1): Int {
        val limiter = FrameRateLimiter(targetFps)
        val random = Random(42)
        val period = 1_000_000_000L / sourceFps
        var count = 0
        for (i in 0 until sourceFps * seconds) {
            val jitter = if (jitterMs == 0) 0L else random.nextLong(-jitterMs * 1_000_000L, jitterMs * 1_000_000L + 1)
            if (limiter.accept(i * period + jitter)) count++
        }
        return count
    }

    @Test
    fun averagesTheRequestedRateInsteadOfRoundingToADivisorOfTheCameraRate() {
        // 30 fps camera, 24 fps target: a naive limiter yields 15 fps (150 frames in 10 s).
        val frames = accepted(sourceFps = 30, targetFps = 24)
        assertTrue("expected ~240 frames, got $frames", frames in 232..248)
    }

    @Test
    fun hotModeStreamStillGetsRoughlyTenFps() {
        val frames = accepted(sourceFps = 30, targetFps = 10)
        assertTrue("expected ~100 frames, got $frames", frames in 95..105)
    }

    @Test
    fun aTargetAboveTheCameraRatePassesEverything() {
        assertEquals(300, accepted(sourceFps = 30, targetFps = 60, jitterMs = 0))
    }

    @Test
    fun theRateCanBeChangedWhileRunning() {
        val limiter = FrameRateLimiter(30)
        val period = 33_333_333L
        var fast = 0
        for (i in 0 until 90) if (limiter.accept(i * period)) fast++
        limiter.setFps(10)
        var slow = 0
        for (i in 90 until 180) if (limiter.accept(i * period)) slow++
        assertTrue("fast=$fast", fast >= 88)
        assertTrue("slow=$slow", slow in 28..32)
    }

    @Test
    fun afterAStallItResumesNormallyWithoutABurst() {
        val limiter = FrameRateLimiter(10)
        assertTrue(limiter.accept(0))
        val afterStall = 60_000_000_000L // a minute later
        assertTrue(limiter.accept(afterStall))
        assertTrue("no catch-up burst", !limiter.accept(afterStall + 10_000_000L))
    }
}

class RestartBackoffTest {

    @Test
    fun growsExponentiallyUpToTheCap() {
        val backoff = RestartBackoff(baseMs = 1_000, maxMs = 30_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { backoff.onFailure() })
    }

    @Test
    fun aSuccessStartsOver() {
        val backoff = RestartBackoff()
        repeat(4) { backoff.onFailure() }
        backoff.onSuccess()
        assertEquals(1_000L, backoff.onFailure())
    }

    @Test
    fun neverOverflowsAfterManyFailures() {
        val backoff = RestartBackoff()
        repeat(500) { assertEquals(true, backoff.onFailure() in 1_000L..30_000L) }
    }
}
