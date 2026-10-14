package com.perch.cam.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class MotionDetectorTest {

    private lateinit var detector: MotionDetector
    private val width = 320
    private val height = 240
    private val frameSize = width * height

    @Before
    fun setUp() {
        detector = MotionDetector(sensitivity = 0.70f)
    }

    private fun createUniformBuffer(lumaValue: Byte): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frameSize)
        for (i in 0 until frameSize) {
            buffer.put(lumaValue)
        }
        buffer.flip()
        return buffer
    }

    @Test
    fun testFirstFrameProducesNoMotion() {
        val buffer = createUniformBuffer(100.toByte())
        val result = detector.processLuminanceBuffer(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1
        )

        assertFalse("First frame should establish baseline and not trigger motion", result.hasMotion)
        assertEquals(0f, result.changedPixelRatio, 0.001f)
    }

    @Test
    fun testStaticSceneProducesNoMotion() {
        val buffer1 = createUniformBuffer(100.toByte())
        detector.processLuminanceBuffer(buffer1, width, height, width, 1)

        val buffer2 = createUniformBuffer(100.toByte())
        val result = detector.processLuminanceBuffer(buffer2, width, height, width, 1)

        assertFalse("Static scene should not trigger motion", result.hasMotion)
        assertEquals(0f, result.changedPixelRatio, 0.001f)
    }

    @Test
    fun testSignificantLuminanceChangeTriggersMotion() {
        // Frame 1: Dark room (luma = 20)
        val buffer1 = createUniformBuffer(20.toByte())
        detector.processLuminanceBuffer(buffer1, width, height, width, 1)

        // Frame 2: Room lights switched on (luma = 200 across full scene)
        val buffer2 = createUniformBuffer(200.toByte())
        val result = detector.processLuminanceBuffer(buffer2, width, height, width, 1)

        assertTrue("Large luminance change should detect motion", result.hasMotion)
        assertTrue("Changed ratio should be significant", result.changedPixelRatio > 0.5f)
    }

    @Test
    fun testResetClearsPreviousFrame() {
        val buffer1 = createUniformBuffer(20.toByte())
        detector.processLuminanceBuffer(buffer1, width, height, width, 1)

        detector.reset()

        // After reset, buffer2 is treated as a new baseline frame
        val buffer2 = createUniformBuffer(200.toByte())
        val result = detector.processLuminanceBuffer(buffer2, width, height, width, 1)

        assertFalse("Frame immediately after reset should be baseline and not trigger motion", result.hasMotion)
    }
}
