package com.perch.cam.detection

import android.graphics.Rect
import android.media.Image
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

data class DetectedFaceInfo(
    val boundingBox: Rect,
    val trackingId: Int?,
    val smilingProbability: Float?,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?
)

data class FaceDetectionResult(
    val faceCount: Int,
    val faces: List<DetectedFaceInfo>,
    /** Size of the upright (rotation applied) frame that [DetectedFaceInfo.boundingBox] refers to. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

class FaceDetectorWrapper {

    private val tag = "Perch:FaceDetector"

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .enableTracking()
        .build()

    private val detector = FaceDetection.getClient(options)

    @OptIn(ExperimentalGetImage::class)
    fun processImageProxy(
        imageProxy: ImageProxy,
        onComplete: (FaceDetectionResult) -> Unit
    ) {
        val mediaImage: Image? = imageProxy.image
        if (mediaImage != null) {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val swap = rotation == 90 || rotation == 270
            val frameWidth = if (swap) imageProxy.height else imageProxy.width
            val frameHeight = if (swap) imageProxy.width else imageProxy.height
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
            detector.process(inputImage)
                .addOnSuccessListener { faces: List<Face> ->
                    val faceList = faces.map { face ->
                        DetectedFaceInfo(
                            boundingBox = face.boundingBox,
                            trackingId = face.trackingId,
                            smilingProbability = face.smilingProbability,
                            leftEyeOpenProbability = face.leftEyeOpenProbability,
                            rightEyeOpenProbability = face.rightEyeOpenProbability
                        )
                    }
                    onComplete(
                        FaceDetectionResult(
                            faceCount = faceList.size,
                            faces = faceList,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight
                        )
                    )
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "ML Kit Face detection error", e)
                    onComplete(FaceDetectionResult(faceCount = 0, faces = emptyList()))
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    fun close() {
        try {
            detector.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing face detector", e)
        }
    }
}
