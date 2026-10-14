package com.perch.cam.streaming

import androidx.camera.core.ImageProxy
import org.webrtc.JavaI420Buffer
import org.webrtc.YuvHelper
import java.nio.ByteBuffer

/** One plane of a YUV_420_888 frame as the camera delivers it. */
class YuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/**
 * Converts CameraX YUV_420_888 frames into upright WebRTC I420 buffers.
 *
 * Browsers behind a media server do not reliably honour the RTP video-orientation extension,
 * so the rotation is baked into the pixels here (libyuv, cheap at 720p).
 */
class I420Converter {

    private var scratch: JavaI420Buffer? = null
    private var released = false

    /** The returned buffer is owned by the caller and must be released (e.g. via VideoFrame.release). */
    fun convert(image: ImageProxy): JavaI420Buffer {
        val planes = image.planes
        return convert(
            image.width, image.height, image.imageInfo.rotationDegrees,
            YuvPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
            YuvPlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
            YuvPlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride)
        )
    }

    @Synchronized
    fun convert(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        y: YuvPlane,
        u: YuvPlane,
        v: YuvPlane
    ): JavaI420Buffer {
        require(rotationDegrees in ROTATIONS) { "rotation must be 0, 90, 180 or 270" }
        // A frame that raced with shutdown must not re-allocate native memory nobody will free.
        check(!released) { "converter was released" }

        val unrotated = if (rotationDegrees == 0) JavaI420Buffer.allocate(width, height) else scratchBuffer(width, height)
        copyPlanes(unrotated, width, height, y, u, v)
        if (rotationDegrees == 0) return unrotated

        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val out = JavaI420Buffer.allocate(if (swap) height else width, if (swap) width else height)
        YuvHelper.I420Rotate(
            unrotated.dataY, unrotated.strideY,
            unrotated.dataU, unrotated.strideU,
            unrotated.dataV, unrotated.strideV,
            out.dataY, out.strideY,
            out.dataU, out.strideU,
            out.dataV, out.strideV,
            width, height, rotationDegrees
        )
        return out
    }

    @Synchronized
    fun release() {
        released = true
        scratch?.release()
        scratch = null
    }

    private fun scratchBuffer(width: Int, height: Int): JavaI420Buffer {
        scratch?.let { if (it.width == width && it.height == height) return it else it.release() }
        return JavaI420Buffer.allocate(width, height).also { scratch = it }
    }

    private fun copyPlanes(dst: JavaI420Buffer, width: Int, height: Int, y: YuvPlane, u: YuvPlane, v: YuvPlane) {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2

        YuvHelper.copyPlane(y.buffer, y.rowStride, dst.dataY, dst.strideY, width, height)
        copyChroma(u, dst.dataU, dst.strideU, chromaWidth, chromaHeight)
        copyChroma(v, dst.dataV, dst.strideV, chromaWidth, chromaHeight)
    }

    private fun copyChroma(src: YuvPlane, dst: ByteBuffer, dstStride: Int, width: Int, height: Int) {
        if (src.pixelStride == 1) {
            YuvHelper.copyPlane(src.buffer, src.rowStride, dst, dstStride, width, height)
            return
        }
        // Semi-planar (NV12/NV21) devices expose interleaved chroma: pick every other byte.
        for (row in 0 until height) {
            val srcBase = row * src.rowStride
            val dstBase = row * dstStride
            for (col in 0 until width) {
                dst.put(dstBase + col, src.buffer.get(srcBase + col * src.pixelStride))
            }
        }
    }

    private companion object {
        val ROTATIONS = setOf(0, 90, 180, 270)
    }
}
