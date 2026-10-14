package com.perch.cam.streaming

import androidx.camera.core.ImageProxy

/**
 * Receives camera analysis frames. Called on the analysis thread; implementations must finish
 * with the frame before returning and must not close it (the camera engine owns it).
 */
fun interface FrameSink {
    fun onFrame(image: ImageProxy)
}
