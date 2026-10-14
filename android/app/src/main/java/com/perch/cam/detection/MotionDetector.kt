package com.perch.cam.detection

import android.util.Log
import androidx.camera.core.ImageProxy
import kotlin.math.abs

class MotionDetector(
    var sensitivity: Float = 0.70f // 0.0 (low) to 1.0 (high)
) {
    private val tag = "Perch:MotionDetector"
    private var previousFrameGray: IntArray? = null
    private val sampleWidth = 80
    private val sampleHeight = 60

    data class MotionResult(
        val hasMotion: Boolean,
        val changedPixelRatio: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun processYPlane(imageProxy: ImageProxy): MotionResult {
        return try {
            val yPlane = imageProxy.planes[0]
            processLuminanceBuffer(
                buffer = yPlane.buffer,
                width = imageProxy.width,
                height = imageProxy.height,
                rowStride = yPlane.rowStride,
                pixelStride = yPlane.pixelStride
            )
        } catch (e: Exception) {
            Log.e(tag, "Error processing frame for motion", e)
            MotionResult(hasMotion = false, changedPixelRatio = 0f)
        }
    }

    fun processLuminanceBuffer(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): MotionResult {
        val currentGray = IntArray(sampleWidth * sampleHeight)
        val xStep = (width / sampleWidth).coerceAtLeast(1)
        val yStep = (height / sampleHeight).coerceAtLeast(1)

        for (sy in 0 until sampleHeight) {
            val srcY = (sy * yStep).coerceAtMost(height - 1)
            val rowOffset = srcY * rowStride
            for (sx in 0 until sampleWidth) {
                val srcX = (sx * xStep).coerceAtMost(width - 1)
                val index = rowOffset + srcX * pixelStride
                val luma = if (index < buffer.limit()) {
                    buffer.get(index).toInt() and 0xFF
                } else 0
                currentGray[sy * sampleWidth + sx] = luma
            }
        }

        val prev = previousFrameGray
        previousFrameGray = currentGray

        if (prev == null || prev.size != currentGray.size) {
            return MotionResult(hasMotion = false, changedPixelRatio = 0f)
        }

        // Pixel threshold based on sensitivity
        val diffThreshold = (50 - (sensitivity * 35)).toInt().coerceIn(10, 50)
        var changedPixels = 0

        for (i in currentGray.indices) {
            if (abs(currentGray[i] - prev[i]) > diffThreshold) {
                changedPixels++
            }
        }

        val ratio = changedPixels.toFloat() / currentGray.size.toFloat()
        // Motion detected if at least 1.5% to 5% of pixels changed
        val minRatio = (0.05f - (sensitivity * 0.035f)).coerceIn(0.01f, 0.06f)
        val detected = ratio >= minRatio

        return MotionResult(
            hasMotion = detected,
            changedPixelRatio = ratio
        )
    }

    fun reset() {
        previousFrameGray = null
    }
}
