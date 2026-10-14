package com.perch.cam.streaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import java.nio.ByteBuffer

/** Pixel-exact checks of the camera-frame to WebRTC conversion on the real native libyuv. */
@RunWith(AndroidJUnit4::class)
class I420ConverterInstrumentedTest {

    private val converter = I420Converter()

    @Before
    fun loadNativeWebRtc() {
        // Loads libjingle_peerconnection_so, which JavaI420Buffer and YuvHelper need.
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(InstrumentationRegistry.getInstrumentation().targetContext)
                .createInitializationOptions()
        )
    }

    private fun yAt(x: Int, y: Int) = (x * 3 + y * 5) and 0xFF
    private fun uAt(cx: Int, cy: Int) = (cx * 7 + cy * 2 + 11) and 0xFF
    private fun vAt(cx: Int, cy: Int) = (cx * 4 + cy * 9 + 101) and 0xFF

    /** Y plane with [padding] junk bytes at the end of every row, like real camera buffers. */
    private fun yPlane(w: Int, h: Int, padding: Int): YuvPlane {
        val stride = w + padding
        val buf = ByteBuffer.allocateDirect(stride * h)
        for (r in 0 until h) for (c in 0 until stride) buf.put(r * stride + c, if (c < w) yAt(c, r).toByte() else 0xAA.toByte())
        return YuvPlane(buf, stride, 1)
    }

    private fun planarChroma(w: Int, h: Int, padding: Int, value: (Int, Int) -> Int): YuvPlane {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val stride = cw + padding
        val buf = ByteBuffer.allocateDirect(stride * ch)
        for (r in 0 until ch) for (c in 0 until stride) buf.put(r * stride + c, if (c < cw) value(c, r).toByte() else 0xAA.toByte())
        return YuvPlane(buf, stride, 1)
    }

    /** NV21-style interleaved chroma (pixelStride 2): the U plane starts at byte 0, V at byte 1. */
    private fun interleavedChroma(w: Int, h: Int, padding: Int): Pair<YuvPlane, YuvPlane> {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val stride = cw * 2 + padding
        val buf = ByteBuffer.allocateDirect(stride * ch + 1)
        for (r in 0 until ch) for (c in 0 until cw) {
            buf.put(r * stride + c * 2, uAt(c, r).toByte())
            buf.put(r * stride + c * 2 + 1, vAt(c, r).toByte())
        }
        val uBuf = buf.duplicate().apply { position(0) }.slice()
        val vBuf = buf.duplicate().apply { position(1) }.slice()
        return YuvPlane(uBuf, stride, 2) to YuvPlane(vBuf, stride, 2)
    }

    private fun JavaI420Buffer.y(x: Int, y: Int) = dataY.get(y * strideY + x).toInt() and 0xFF
    private fun JavaI420Buffer.u(x: Int, y: Int) = dataU.get(y * strideU + x).toInt() and 0xFF
    private fun JavaI420Buffer.v(x: Int, y: Int) = dataV.get(y * strideV + x).toInt() and 0xFF

    private fun convertPlanar(w: Int, h: Int, rotation: Int, padding: Int = 0) = converter.convert(
        w, h, rotation,
        yPlane(w, h, padding),
        planarChroma(w, h, padding, ::uAt),
        planarChroma(w, h, padding, ::vAt)
    )

    @Test
    fun unrotatedFrameIsCopiedExactlyIgnoringRowPadding() {
        val out = convertPlanar(64, 48, 0, padding = 16)
        assertEquals(64, out.width)
        assertEquals(48, out.height)
        for (r in 0 until 48) for (c in 0 until 64) assertEquals("Y($c,$r)", yAt(c, r), out.y(c, r))
        for (r in 0 until 24) for (c in 0 until 32) {
            assertEquals("U($c,$r)", uAt(c, r), out.u(c, r))
            assertEquals("V($c,$r)", vAt(c, r), out.v(c, r))
        }
        out.release()
    }

    @Test
    fun interleavedChromaIsSplitIntoSeparatePlanes() {
        val (u, v) = interleavedChroma(64, 48, padding = 8)
        val out = converter.convert(64, 48, 0, yPlane(64, 48, 0), u, v)
        for (r in 0 until 24) for (c in 0 until 32) {
            assertEquals("U($c,$r)", uAt(c, r), out.u(c, r))
            assertEquals("V($c,$r)", vAt(c, r), out.v(c, r))
        }
        out.release()
    }

    @Test
    fun rotate90IsClockwiseAndSwapsDimensions() {
        val w = 64
        val h = 48
        val out = convertPlanar(w, h, 90)
        assertEquals(h, out.width)
        assertEquals(w, out.height)
        // The source's top-left pixel ends up top-right; clockwise: out(c, r) = src(col = r, row = h-1-c).
        for (r in 0 until w) for (c in 0 until h) assertEquals("Y($c,$r)", yAt(r, h - 1 - c), out.y(c, r))
        out.release()
    }

    @Test
    fun rotate180FlipsBothAxes() {
        val w = 64
        val h = 48
        val out = convertPlanar(w, h, 180)
        assertEquals(w, out.width)
        assertEquals(h, out.height)
        for (r in 0 until h) for (c in 0 until w) assertEquals("Y($c,$r)", yAt(w - 1 - c, h - 1 - r), out.y(c, r))
        out.release()
    }

    @Test
    fun rotate270IsCounterClockwise() {
        val w = 64
        val h = 48
        val out = convertPlanar(w, h, 270)
        assertEquals(h, out.width)
        assertEquals(w, out.height)
        for (r in 0 until w) for (c in 0 until h) assertEquals("Y($c,$r)", yAt(w - 1 - r, c), out.y(c, r))
        out.release()
    }

    @Test
    fun chromaFollowsTheSameRotationAsLuma() {
        val w = 64
        val h = 48
        val out = convertPlanar(w, h, 90)
        val cw = w / 2
        val ch = h / 2
        for (r in 0 until cw) for (c in 0 until ch) {
            assertEquals("U($c,$r)", uAt(r, ch - 1 - c), out.u(c, r))
            assertEquals("V($c,$r)", vAt(r, ch - 1 - c), out.v(c, r))
        }
        out.release()
    }

    @Test
    fun oddDimensionsDoNotCrashAndKeepTheirSize() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val out = convertPlanar(33, 21, rotation, padding = 3)
            val swap = rotation == 90 || rotation == 270
            assertEquals(if (swap) 21 else 33, out.width)
            assertEquals(if (swap) 33 else 21, out.height)
            out.release()
        }
    }

    @Test
    fun reusesItsScratchBufferAcrossFramesAndSurvivesResolutionChanges() {
        repeat(5) { convertPlanar(64, 48, 90).release() }
        convertPlanar(128, 72, 90).release() // size change swaps the scratch buffer
        val out = convertPlanar(64, 48, 90)
        assertEquals(yAt(0, 47), out.y(0, 0))
        out.release()
        converter.release()
    }

    @Test
    fun rejectsRotationsThatAreNotMultiplesOfNinety() {
        val e = runCatching { convertPlanar(64, 48, 45) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
