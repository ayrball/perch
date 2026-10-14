package com.perch.cam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.perch.cam.data.PerchPreferences
import com.perch.cam.detection.AlertManager
import com.perch.cam.detection.FaceDetectionResult
import com.perch.cam.detection.FaceDetectorWrapper
import com.perch.cam.detection.MotionDetector
import com.perch.cam.streaming.FrameSink
import com.perch.cam.streaming.StreamState
import com.perch.cam.util.PermissionHelper
import com.perch.cam.util.RestartBackoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A detected face as fractions (0..1) of the upright video frame. */
data class FaceBox(val x: Float, val y: Float, val w: Float, val h: Float)

data class CameraEngineState(
    val isInitialized: Boolean = false,
    val isRecording: Boolean = false,
    val isStreaming: Boolean = false,
    val streamState: StreamState = StreamState.IDLE,
    val isMotionDetectionActive: Boolean = true,
    val isFaceDetectionActive: Boolean = true,
    val activeFaceCount: Int = 0,
    val faces: List<FaceBox> = emptyList(),
    val motionDetected: Boolean = false,
    val currentRecordingSeconds: Long = 0L,
    val isFlashlightOn: Boolean = false,
    val isFrontCamera: Boolean = false,
    /** The phone is overheating: analysis is reduced and recording drops to 720p. */
    val isThermalThrottled: Boolean = false
)

class CameraEngine(
    private val context: Context,
    private val segmentedRecorder: SegmentedRecorder,
    private val alertManager: AlertManager,
    private val preferences: PerchPreferences
) {
    private val tag = "Perch:CameraEngine"
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var currentSegmentFile: File? = null
    private var segmentStartTime = 0L

    // Tracks whether the user/app wants continuous recording active
    @Volatile
    private var isContinuousRecordingActive = false

    // Segment-level detection flags (latches true if detected during the current segment)
    @Volatile
    private var segmentHadMotion = false
    @Volatile
    private var segmentHadFace = false

    // Camera use cases must be re-bound for a different camera, quality or thermal mode. A bind
    // while recording would kill the segment, so we finalize it first and re-bind in Finalize.
    private var rebindPending = false

    // False while the camera could not be bound (unsupported combo, held by another app).
    private var useCasesBound = false
    private val restartBackoff = RestartBackoff()

    // Watchdog inputs. Another app taking the camera can leave CameraX closed or a recorder
    // that "records" without ever receiving a video frame; neither raises an error by itself.
    private var cameraOpen = false
    private var cameraNotOpenSinceMs = 0L

    // CameraX retries "camera in use"-type errors on its own, roughly once a second.
    private var cameraRetrying = false
    private var lastRecordingEventMs = 0L
    private var cameraStateLive: LiveData<CameraState>? = null
    private val cameraStateObserver = Observer<CameraState> { state ->
        val open = state.type == CameraState.Type.OPEN
        if (open) {
            cameraOpen = true
        } else if (cameraOpen) {
            cameraOpen = false
            cameraNotOpenSinceMs = SystemClock.elapsedRealtime()
        }
        cameraRetrying = state.error?.code in RECOVERABLE_CAMERA_ERRORS
        state.error?.let { Log.w(tag, "Camera state ${state.type} with error code ${it.code}") }
    }
    private var appliedQualityPref: String = preferences.settingsFlow.value.videoQuality

    @Volatile
    private var thermalThrottled = false

    @Volatile
    var frameSink: FrameSink? = null

    private var analysisFrameCounter = 0L
    private var lastFaceScanMs = 0L

    private val motionDetector = MotionDetector()
    private val faceDetector = FaceDetectorWrapper()

    private val _engineState = MutableStateFlow(CameraEngineState())
    val engineState: StateFlow<CameraEngineState> = _engineState.asStateFlow()

    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var boundLifecycleOwner: LifecycleOwner? = null

    init {
        // Observe settings changes reactively
        coroutineScope.launch {
            preferences.settingsFlow.collect { settings ->
                motionDetector.sensitivity = settings.motionSensitivity
                _engineState.update {
                    it.copy(
                        isMotionDetectionActive = settings.motionDetectionEnabled,
                        isFaceDetectionActive = settings.faceDetectionEnabled
                    )
                }
                if (settings.videoQuality != appliedQualityPref && _engineState.value.isInitialized) {
                    requestRebind()
                }
            }
        }
    }

    fun initialize(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider? = null,
        onInitialized: (() -> Unit)? = null
    ) {
        boundLifecycleOwner = lifecycleOwner
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner, surfaceProvider)
                _engineState.update { it.copy(isInitialized = true) }
                onInitialized?.invoke()

                // Auto-start recording if configured in preferences
                if (preferences.settingsFlow.value.continuousRecording && !isContinuousRecordingActive) {
                    startContinuousRecording()
                }
                startWatchdog()
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize CameraEngine", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null

    fun attachSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        pendingSurfaceProvider = surfaceProvider
        previewUseCase?.setSurfaceProvider(surfaceProvider)
    }

    private fun targetQuality(): Quality {
        val preferred = when (preferences.settingsFlow.value.videoQuality) {
            "4K" -> Quality.UHD
            "720p" -> Quality.HD
            else -> Quality.FHD
        }
        // A hot phone drops to 720p: less encoder load, less heat.
        return if (thermalThrottled) Quality.HD else preferred
    }

    /**
     * The encoder's default is ~12 Mbps at 1080p (90 MB per minute on a Pixel 10a), far more than
     * a fixed security camera needs and enough to fill the phone in about an hour and a half.
     */
    private fun targetBitrate(quality: Quality): Int = when (quality) {
        Quality.UHD -> 20_000_000
        Quality.FHD -> 6_000_000
        Quality.HD -> 3_000_000
        else -> 1_500_000
    }

    private fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider?
    ) {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        useCasesBound = false
        appliedQualityPref = preferences.settingsFlow.value.videoQuality

        val providerToUse = surfaceProvider ?: pendingSurfaceProvider
        // 1. Preview (can have null surface provider when UI is hidden)
        previewUseCase = Preview.Builder().build().also {
            if (providerToUse != null) {
                it.setSurfaceProvider(providerToUse)
            }
        }

        // 2. Video Capture with high performance Recorder
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(targetQuality(), Quality.FHD, Quality.HD, Quality.SD)
        )
        // No custom executor: CameraX's default IO executor keeps encoder/muxer work (and the
        // heavy Finalize step) off the main thread. Our own listener below still runs on main.
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setTargetVideoEncodingBitRate(targetBitrate(targetQuality()))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // 3. Image Analysis for motion, ML Kit faces and the live stream (YUV_420_888).
        // 720p keeps the stream sharp; motion detection subsamples to a tiny grid anyway.
        val analysisResolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            processFrameForAnalysis(imageProxy)
        }

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                currentCameraSelector,
                previewUseCase,
                videoCapture,
                imageAnalysis
            )
            cameraStateLive?.removeObserver(cameraStateObserver)
            cameraOpen = false
            cameraNotOpenSinceMs = SystemClock.elapsedRealtime()
            cameraStateLive = camera?.cameraInfo?.cameraState?.also { it.observe(lifecycleOwner, cameraStateObserver) }

            val front = currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
            val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
            // The torch turns off when use cases are re-bound; restore it where it still applies.
            val torchWanted = _engineState.value.isFlashlightOn && hasFlash
            camera?.cameraControl?.enableTorch(torchWanted)
            _engineState.update { it.copy(isFrontCamera = front, isFlashlightOn = torchWanted) }
            motionDetector.reset()
            useCasesBound = true
            Log.d(tag, "Camera use cases bound (front=$front, quality=${targetQuality()})")
        } catch (e: Exception) {
            Log.e(tag, "Use case binding failed; will retry in ${BIND_RETRY_MS / 1000}s", e)
            scheduleBindRetry()
        }
    }

    /**
     * A failed bind (camera busy, unsupported combination) must not leave a "recording" service
     * that records nothing: keep retrying, and resume recording once the camera comes back.
     */
    private fun scheduleBindRetry() {
        coroutineScope.launch {
            delay(BIND_RETRY_MS)
            if (useCasesBound) return@launch
            rebindPending = true
            rebindIfPending()
            if (useCasesBound && preferences.settingsFlow.value.continuousRecording && activeRecording == null) {
                startContinuousRecording()
            }
        }
    }

    private fun startWatchdog() {
        coroutineScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    watchdogTick()
                } catch (e: Exception) {
                    Log.e(tag, "Watchdog error", e)
                }
            }
        }
    }

    /** Detects "recording" that is not actually receiving video and repairs it. */
    private fun watchdogTick() {
        if (!useCasesBound || !_engineState.value.isInitialized) return
        val now = SystemClock.elapsedRealtime()

        // 1. The camera is not open. While CameraX is retrying a recoverable error (another app
        //    is using the camera) it reconnects by itself the moment the camera is free, and
        //    tearing the session down would only add churn, so wait much longer in that case.
        if (!cameraOpen) {
            val limit = if (cameraRetrying) BUSY_CAMERA_LIMIT_MS else STALL_LIMIT_MS
            if (now - cameraNotOpenSinceMs > limit) {
                Log.w(tag, "Camera has not been open for ${(now - cameraNotOpenSinceMs) / 1000}s; re-binding")
                cameraNotOpenSinceMs = now
                forceRebind()
            }
            return
        }

        // 2. The camera is open and a segment is "recording", yet no progress events arrive:
        //    no video frames are flowing (the recorder is only buffering audio).
        if (activeRecording != null && now - lastRecordingEventMs > STALL_LIMIT_MS) {
            Log.w(tag, "Recording made no progress for ${(now - lastRecordingEventMs) / 1000}s; re-binding")
            lastRecordingEventMs = now
            forceRebind()
            return
        }

        // 3. We should be recording but nothing is running (e.g. a restart was dropped).
        if (isContinuousRecordingActive && activeRecording == null &&
            now - lastRecordingEventMs > STALL_LIMIT_MS
        ) {
            Log.w(tag, "Continuous recording is on but nothing is recording; restarting")
            startContinuousRecording()
        }
    }

    /**
     * Re-binds immediately, without waiting for the current recording to finalize (a stalled
     * recorder may never do so). Whatever the stalled segment wrote is still indexed when its
     * Finalize event eventually arrives.
     */
    private fun forceRebind() {
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Stopping stalled recording failed", e)
        }
        activeRecording = null
        _engineState.update { it.copy(isRecording = false, currentRecordingSeconds = 0L) }
        rebindPending = true
        rebindIfPending()
        if (useCasesBound && isContinuousRecordingActive) startContinuousRecording()
    }

    /**
     * Re-binds the camera on the next safe moment. If a segment is recording it is stopped
     * cleanly (and saved); the Finalize handler then re-binds and starts the next segment.
     */
    private fun requestRebind() {
        coroutineScope.launch {
            rebindPending = true
            val recording = activeRecording
            if (recording != null) {
                try {
                    recording.stop()
                } catch (e: Exception) {
                    Log.e(tag, "Error stopping segment for re-bind", e)
                    activeRecording = null
                    rebindIfPending()
                }
            } else {
                rebindIfPending()
            }
        }
    }

    private fun rebindIfPending() {
        if (!rebindPending) return
        rebindPending = false
        boundLifecycleOwner?.let { bindCameraUseCases(it, null) }
    }

    /** Called with the latest thermal state; changes take effect on a fresh recording segment. */
    fun setThermalThrottle(throttled: Boolean) {
        if (thermalThrottled == throttled) return
        thermalThrottled = throttled
        _engineState.update { it.copy(isThermalThrottled = throttled) }
        Log.w(tag, if (throttled) "Thermal throttle ON: reducing analysis and quality" else "Thermal throttle OFF")
        if (_engineState.value.isInitialized) requestRebind()
    }

    private fun processFrameForAnalysis(imageProxy: ImageProxy) {
        try {
            val frame = analysisFrameCounter++
            val state = _engineState.value
            val throttled = thermalThrottled

            // 0. Live stream: convert synchronously, the frame is closed below.
            frameSink?.onFrame(imageProxy)

            // 1. Motion detection on the raw Y plane. Every 3rd frame is plenty when hot.
            if (state.isMotionDetectionActive && (!throttled || frame % 3 == 0L)) {
                val motionResult = motionDetector.processYPlane(imageProxy)
                if (motionResult.hasMotion) {
                    segmentHadMotion = true
                    _engineState.update { it.copy(motionDetected = true) }
                    alertManager.onMotionDetected(motionResult.changedPixelRatio)
                } else {
                    _engineState.update { it.copy(motionDetected = false) }
                }
            }

            // 2. Face detection is the most expensive step: ~5 scans/s, 1/s when hot.
            val now = SystemClock.elapsedRealtime()
            val scanInterval = if (throttled) FACE_SCAN_INTERVAL_HOT_MS else FACE_SCAN_INTERVAL_MS
            if (state.isFaceDetectionActive && now - lastFaceScanMs >= scanInterval) {
                lastFaceScanMs = now
                faceDetector.processImageProxy(imageProxy) { result -> onFaceResult(result) } // closes the frame
                return
            }
            if (!state.isFaceDetectionActive && state.activeFaceCount > 0) {
                _engineState.update { it.copy(activeFaceCount = 0, faces = emptyList()) }
            }
            imageProxy.close()
        } catch (e: Exception) {
            Log.e(tag, "Error analyzing frame", e)
            try {
                imageProxy.close()
            } catch (_: Exception) {}
        }
    }

    private fun onFaceResult(result: FaceDetectionResult) {
        if (result.faceCount > 0) {
            segmentHadFace = true
            alertManager.onFaceDetected(result.faceCount)
        }
        val boxes = if (result.frameWidth > 0 && result.frameHeight > 0) {
            result.faces.map { face ->
                val b = face.boundingBox
                FaceBox(
                    x = (b.left.toFloat() / result.frameWidth).coerceIn(0f, 1f),
                    y = (b.top.toFloat() / result.frameHeight).coerceIn(0f, 1f),
                    w = (b.width().toFloat() / result.frameWidth).coerceIn(0f, 1f),
                    h = (b.height().toFloat() / result.frameHeight).coerceIn(0f, 1f)
                )
            }
        } else emptyList()
        _engineState.update { it.copy(activeFaceCount = result.faceCount, faces = boxes) }
    }

    @SuppressLint("MissingPermission")
    fun startContinuousRecording() {
        val capture = videoCapture ?: return
        if (!useCasesBound || activeRecording != null) return

        isContinuousRecordingActive = true
        lastRecordingEventMs = SystemClock.elapsedRealtime()
        segmentHadMotion = false
        segmentHadFace = false

        val settings = preferences.settingsFlow.value
        val enableAudio = settings.recordAudio && PermissionHelper.hasAudioPermission(context)
        val segmentMaxSeconds = settings.segmentDurationMinutes * 60L

        val nextFile = segmentedRecorder.createNextRecordingFile()
        currentSegmentFile = nextFile
        segmentStartTime = System.currentTimeMillis()

        val outputOptions = FileOutputOptions.Builder(nextFile).build()
        var pendingRecording = capture.output.prepareRecording(context, outputOptions)
        if (enableAudio) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        var recordingHandle: Recording? = null
        val newRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    lastRecordingEventMs = SystemClock.elapsedRealtime()
                    _engineState.update { it.copy(isRecording = true) }
                    Log.d(tag, "Recording segment started: ${nextFile.name}")
                }
                is VideoRecordEvent.Status -> {
                    lastRecordingEventMs = SystemClock.elapsedRealtime()
                    val durationSeconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    _engineState.update { it.copy(currentRecordingSeconds = durationSeconds) }

                    // CHUNK SPLITTING: If current segment has reached configured duration, stop it to rollover
                    if (isContinuousRecordingActive && durationSeconds >= segmentMaxSeconds) {
                        Log.d(tag, "Segment reached $durationSeconds seconds (target: $segmentMaxSeconds s). Rolling over...")
                        try {
                            recordingHandle?.stop()
                        } catch (e: Exception) {
                            Log.e(tag, "Error stopping segment for rollover", e)
                        }
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    val durationSec = (System.currentTimeMillis() - segmentStartTime) / 1000L
                    if (event.hasError()) Log.e(tag, "Video recording ended with error ${event.error}")
                    // Errors such as SOURCE_INACTIVE (camera re-bound or taken by another app)
                    // still leave a playable file; onSegmentCompleted skips empty/missing ones.
                    Log.d(tag, "Recording segment finalized: ${nextFile.name} ($durationSec s, error=${event.hasError()})")
                    segmentedRecorder.onSegmentCompleted(
                        file = nextFile,
                        durationSeconds = durationSec,
                        hasMotion = segmentHadMotion,
                        hasFace = segmentHadFace
                    )
                    val failedFast = event.hasError() && durationSec < MIN_HEALTHY_SEGMENT_SECONDS

                    // Only clear activeRecording if it hasn't been superseded
                    if (activeRecording == recordingHandle) {
                        activeRecording = null
                    }
                    _engineState.update { it.copy(isRecording = false, currentRecordingSeconds = 0L) }

                    // Camera/quality/thermal changes wait for a segment boundary
                    rebindIfPending()

                    // Auto-start next segment only if continuous recording is still active. A
                    // segment that failed within seconds backs off so a camera that cannot be
                    // opened does not turn into a tight start/fail loop.
                    if (isContinuousRecordingActive && _engineState.value.isInitialized) {
                        if (failedFast) {
                            val wait = restartBackoff.onFailure()
                            Log.w(tag, "Segment failed immediately; retrying in ${wait}ms")
                            coroutineScope.launch {
                                delay(wait)
                                if (isContinuousRecordingActive && activeRecording == null) startContinuousRecording()
                            }
                        } else {
                            restartBackoff.onSuccess()
                            startContinuousRecording()
                        }
                    }
                }
            }
        }
        recordingHandle = newRecording
        activeRecording = newRecording
    }

    fun stopRecording() {
        isContinuousRecordingActive = false
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping recording", e)
        } finally {
            activeRecording = null
            _engineState.update { it.copy(isRecording = false, currentRecordingSeconds = 0L) }
        }
    }

    /** Sets the torch; returns false when the active camera has no flash unit (e.g. front camera). */
    fun setFlashlight(on: Boolean): Boolean {
        val cam = camera ?: return false
        if (!cam.cameraInfo.hasFlashUnit()) return false
        cam.cameraControl.enableTorch(on)
        _engineState.update { it.copy(isFlashlightOn = on) }
        return true
    }

    fun toggleFlashlight() {
        setFlashlight(!_engineState.value.isFlashlightOn)
    }

    fun switchCamera() {
        val provider = cameraProvider ?: return
        val target = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        if (!provider.hasCamera(target)) {
            Log.w(tag, "Requested camera is not available on this device")
            return
        }
        currentCameraSelector = target
        requestRebind()
    }

    fun toggleMotionDetection(enabled: Boolean) {
        preferences.updateSettings { it.copy(motionDetectionEnabled = enabled) }
    }

    fun toggleFaceDetection(enabled: Boolean) {
        preferences.updateSettings { it.copy(faceDetectionEnabled = enabled) }
    }

    /** Turns live streaming on or off. The recording service reacts to the setting. */
    fun toggleStreaming(enabled: Boolean) {
        preferences.updateSettings { it.copy(streamingEnabled = enabled) }
    }

    /** Mirrors the publisher's connection state into the UI state. */
    fun onStreamStateChanged(state: StreamState) {
        _engineState.update { it.copy(streamState = state, isStreaming = state == StreamState.LIVE) }
    }

    fun release() {
        stopRecording()
        // Order matters: nothing may still be handing frames to native code (the stream's
        // converter, ML Kit) when the caller frees it, so unbind and drain the analysis
        // thread before returning.
        frameSink = null
        cameraStateLive?.removeObserver(cameraStateObserver)
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        if (!analysisExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            Log.w(tag, "Analysis thread did not stop in time")
        }
        faceDetector.close()
        coroutineScope.cancel()
    }

    private companion object {
        const val BIND_RETRY_MS = 5_000L
        const val WATCHDOG_INTERVAL_MS = 5_000L
        const val STALL_LIMIT_MS = 15_000L
        const val BUSY_CAMERA_LIMIT_MS = 120_000L

        // CameraState.StateError: MAX_CAMERAS_IN_USE, CAMERA_IN_USE, OTHER_RECOVERABLE_ERROR
        val RECOVERABLE_CAMERA_ERRORS = setOf(1, 2, 3)
        const val MIN_HEALTHY_SEGMENT_SECONDS = 2L
        const val FACE_SCAN_INTERVAL_MS = 200L
        const val FACE_SCAN_INTERVAL_HOT_MS = 1_000L
    }
}
