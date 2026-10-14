package com.perch.cam.streaming

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class StreamState { IDLE, CONNECTING, LIVE, RECONNECTING }

data class StreamStatus(val state: StreamState = StreamState.IDLE, val detail: String? = null)

/**
 * Publishes the camera feed to a media server (MediaMTX) over WebRTC using WHIP.
 *
 * Frames arrive through [onFrame] from the camera analysis stream, so the phone keeps recording
 * and detecting with the same camera session. The publisher reconnects with backoff on its own
 * until [stop] is called.
 */
class WebRTCPublisher(
    context: Context,
    private val scope: CoroutineScope,
    private val whip: WhipClient = WhipClient()
) : FrameSink {

    private val tag = "Perch:WebRTCPublisher"
    private val appContext = context.applicationContext

    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    private var job: Job? = null
    private val converter = I420Converter()

    @Volatile
    private var videoSource: VideoSource? = null

    private val limiter = FrameRateLimiter(StreamConfig.DEFAULT_FPS)

    /** Lowers the sent frame rate, e.g. while the phone is overheating. */
    fun setFrameRateLimit(fps: Int) = limiter.setFps(fps)

    @Synchronized
    fun start(config: StreamConfig) {
        stop()
        setFrameRateLimit(config.fps)
        _status.value = StreamStatus(StreamState.CONNECTING)
        job = scope.launch(Dispatchers.IO) { supervise(config) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _status.value = StreamStatus(StreamState.IDLE)
    }

    fun release() {
        stop()
        converter.release()
    }

    override fun onFrame(image: ImageProxy) {
        val source = videoSource ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        if (!limiter.accept(now)) return

        val buffer = try {
            converter.convert(image)
        } catch (e: Exception) {
            Log.w(tag, "Dropping frame: conversion failed", e)
            return
        }
        val frame = VideoFrame(buffer, 0, now)
        try {
            source.capturerObserver.onFrameCaptured(frame)
        } finally {
            frame.release()
        }
    }

    private suspend fun supervise(config: StreamConfig) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val startedAt = SystemClock.elapsedRealtime()
            val reason = try {
                runSession(config)
                "connection closed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Stream session ended: ${e.message}")
                e.message ?: e.javaClass.simpleName
            } catch (e: LinkageError) {
                // The native WebRTC library failed to load: keep the app alive and retry slowly.
                Log.e(tag, "WebRTC native library unavailable", e)
                "WebRTC is unavailable on this device"
            }

            // A session that stayed up for a while resets the backoff.
            if (SystemClock.elapsedRealtime() - startedAt > STABLE_SESSION_MS) attempt = 0
            attempt++
            _status.value = StreamStatus(StreamState.RECONNECTING, reason)
            delay(backoffMs(attempt))
            _status.value = StreamStatus(StreamState.CONNECTING, reason)
        }
    }

    private suspend fun runSession(config: StreamConfig) {
        val factory = PeerFactory.get(appContext)
        val dropped = CompletableDeferred<String>()
        val gathered = CompletableDeferred<Unit>()
        var peer: PeerConnection? = null
        // Timers started by native callbacks belong to this session, so they cannot outlive
        // (and touch) a peer connection that has already been disposed.
        val sessionScope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())

        val observer = object : PeerObserverAdapter() {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(tag, "Peer connection state: $state")
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED ->
                        _status.value = StreamStatus(StreamState.LIVE)
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED ->
                        dropped.complete("peer connection $state")
                    PeerConnection.PeerConnectionState.DISCONNECTED ->
                        // Often transient on Wi-Fi roaming; only give up if it does not recover.
                        sessionScope.launch {
                            delay(DISCONNECT_GRACE_MS)
                            if (!dropped.isCompleted &&
                                peer?.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED
                            ) {
                                dropped.complete("peer connection lost")
                            }
                        }
                    else -> Unit
                }
            }
        }

        val source = factory.createVideoSource(false)
        source.adaptOutputFormat(config.maxWidth, config.maxHeight, config.fps)
        source.capturerObserver.onCapturerStarted(true)
        val track = factory.createVideoTrack("perch-video", source)

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val pc = factory.createPeerConnection(rtcConfig, observer)
            ?: throw IOException("Could not create peer connection")
        peer = pc

        var resourceUrl: String? = null
        try {
            pc.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            )

            val offer = pc.createOfferSuspend()
            pc.setLocalDescriptionSuspend(offer)
            // Non-trickle: wait for the (LAN) candidates so the offer carries them all.
            withTimeoutOrNull(ICE_GATHER_TIMEOUT_MS) { gathered.await() }

            val localSdp = pc.localDescription?.description ?: throw IOException("No local SDP")
            val session = withContext(Dispatchers.IO) { whip.publish(config.whipUrl, localSdp, config.bearerToken) }
            resourceUrl = session.resourceUrl

            pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.ANSWER, session.answerSdp))
            applyEncoderLimits(pc, config)

            videoSource = source // start feeding frames
            throw IOException(dropped.await())
        } finally {
            // A slow-to-cancel old session must not clear the source a newer session installed.
            if (videoSource === source) videoSource = null
            sessionScope.cancel()
            withContext(NonCancellable) {
                resourceUrl?.let { runCatching { whip.terminate(it, config.bearerToken) } }
                runCatching { pc.close() }
                runCatching { pc.dispose() }
                runCatching { track.dispose() }
                runCatching { source.dispose() }
            }
        }
    }

    private fun applyEncoderLimits(pc: PeerConnection, config: StreamConfig) {
        val sender = pc.senders.firstOrNull { it.track()?.kind() == "video" } ?: return
        val params = sender.parameters
        params.encodings.forEach {
            it.maxBitrateBps = config.bitrateKbps * 1000
            it.maxFramerate = config.fps
        }
        sender.setParameters(params)
    }

    companion object {
        private const val ICE_GATHER_TIMEOUT_MS = 3_000L
        private const val DISCONNECT_GRACE_MS = 5_000L
        private const val STABLE_SESSION_MS = 30_000L

        /** 1s, 2s, 4s, 8s, then 15s between attempts. */
        fun backoffMs(attempt: Int): Long = minOf(1_000L shl (attempt - 1).coerceIn(0, 4), 15_000L)
    }
}

/** Process-wide WebRTC factory; creating it is expensive and it is safe to keep forever. */
private object PeerFactory {
    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun get(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
        factory ?: build(context).also { factory = it }
    }

    private fun build(context: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val egl = EglBase.create()
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }
}

/** PeerConnection.Observer with every callback defaulted, so sessions override only what they use. */
private open class PeerObserverAdapter : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidate(candidate: IceCandidate) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: MediaStream) = Unit
    override fun onRemoveStream(stream: MediaStream) = Unit
    override fun onDataChannel(channel: DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
}

private suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SdpResultObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) =
                cont.resumeWithException(IOException("createOffer failed: $error"))
        }, MediaConstraints())
    }

private suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpResultObserver() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) =
                cont.resumeWithException(IOException("setLocalDescription failed: $error"))
        }, sdp)
    }

private suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpResultObserver() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) =
                cont.resumeWithException(IOException("setRemoteDescription failed: $error"))
        }, sdp)
    }

private open class SdpResultObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}
