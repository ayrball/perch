package com.perch.cam.util

import com.perch.cam.data.PerchPreferences
import com.perch.cam.streaming.WebRTCPublisher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGovernorTest {

    @Test
    fun throttlesAtThresholdAndOnlyResumesWellBelowIt() {
        val governor = ThermalGovernor(throttleAtCelsius = 45f, resumeAtCelsius = 41f)

        assertFalse(governor.update(30f))
        assertFalse(governor.isThrottled)

        assertTrue("crossing the threshold reports a change", governor.update(45f))
        assertTrue(governor.isThrottled)

        // Hovering between the thresholds must not flap.
        assertFalse(governor.update(43f))
        assertTrue(governor.isThrottled)
        assertFalse(governor.update(41.5f))
        assertTrue(governor.isThrottled)

        assertTrue(governor.update(41f))
        assertFalse(governor.isThrottled)
        assertFalse(governor.update(43f))
        assertFalse(governor.isThrottled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedThresholds() {
        ThermalGovernor(throttleAtCelsius = 40f, resumeAtCelsius = 45f)
    }
}

class ByteFormatTest {

    @Test
    fun formatsUnits() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("4.2 GB", formatBytes((4.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("150 GB", formatBytes(150L * 1024 * 1024 * 1024))
    }
}

class TokenAndBackoffTest {

    @Test
    fun generatedTokensAreLongUniqueAndUnambiguous() {
        val a = PerchPreferences.generateToken()
        val b = PerchPreferences.generateToken()
        assertEquals(32, a.length)
        assertNotEquals(a, b)
        assertTrue(a.none { it in "0O1lI" })
    }

    @Test
    fun streamBackoffGrowsThenCaps() {
        assertEquals(1_000L, WebRTCPublisher.backoffMs(1))
        assertEquals(2_000L, WebRTCPublisher.backoffMs(2))
        assertEquals(4_000L, WebRTCPublisher.backoffMs(3))
        assertEquals(8_000L, WebRTCPublisher.backoffMs(4))
        assertEquals(15_000L, WebRTCPublisher.backoffMs(5))
        assertEquals(15_000L, WebRTCPublisher.backoffMs(50))
    }
}
