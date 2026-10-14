package com.perch.cam.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.perch.cam.MainActivity
import com.perch.cam.PerchApp
import com.perch.cam.R
import com.perch.cam.backup.BackupScheduler
import com.perch.cam.camera.CameraEngine
import com.perch.cam.camera.SegmentedRecorder
import com.perch.cam.control.ControlApi
import com.perch.cam.control.ControlServer
import com.perch.cam.control.ServiceControlBackend
import com.perch.cam.control.SirenPlayer
import com.perch.cam.data.PerchPreferences
import com.perch.cam.detection.AlertManager
import com.perch.cam.storage.RetentionManager
import com.perch.cam.streaming.StreamConfig
import com.perch.cam.streaming.StreamState
import com.perch.cam.streaming.WebRTCPublisher
import com.perch.cam.util.BatteryMonitor
import com.perch.cam.util.BatteryStatus
import com.perch.cam.util.PermissionHelper
import com.perch.cam.util.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecordingService : Service() {

    private val tag = "Perch:RecordingService"
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var wakeLockManager: WakeLockManager
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var backupScheduler: BackupScheduler
    private lateinit var segmentedRecorder: SegmentedRecorder
    private lateinit var alertManager: AlertManager
    private lateinit var preferences: PerchPreferences
    private lateinit var retention: RetentionManager
    private lateinit var publisher: WebRTCPublisher
    private lateinit var siren: SirenPlayer
    private val serviceLifecycleOwner = ServiceLifecycleOwner()
    lateinit var cameraEngine: CameraEngine
        private set

    private var controlServer: ControlServer? = null
    private var timerJob: Job? = null
    private var serviceStartTime = 0L
    private var shutDown = false

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService

        /**
         * Called (on the main thread) when the service has shut itself down. A bound client keeps
         * a stopped service alive, so the Activity must unbind when it hears this.
         */
        fun setOnShutdown(listener: (() -> Unit)?) {
            onShutdownListener = listener
        }
    }

    private var onShutdownListener: (() -> Unit)? = null

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "RecordingService onCreate")

        val database = PerchApp.instance.database
        preferences = PerchPreferences(this)
        wakeLockManager = WakeLockManager(this)
        backupScheduler = BackupScheduler(this)
        segmentedRecorder = SegmentedRecorder(this, backupScheduler)
        alertManager = AlertManager(this, database.alertDao(), preferences)
        cameraEngine = CameraEngine(this, segmentedRecorder, alertManager, preferences)
        retention = RetentionManager(database.recordingDao(), preferences, SegmentedRecorder.recordingDirectory(this))
        publisher = WebRTCPublisher(this, serviceScope)
        siren = SirenPlayer(this)

        cameraEngine.frameSink = publisher
        serviceLifecycleOwner.start()
        cameraEngine.initialize(serviceLifecycleOwner, null)

        batteryMonitor = BatteryMonitor(this) { status: BatteryStatus ->
            // A hot phone sheds work: lower analysis rate, 720p recording and a slower stream.
            cameraEngine.setThermalThrottle(status.isOverheating)
            publisher.setFrameRateLimit(if (status.isOverheating) HOT_STREAM_FPS else StreamConfig.DEFAULT_FPS)
        }
        batteryMonitor.start()

        observeSettings()
        startRetentionLoop()
    }

    private fun observeSettings() {
        serviceScope.launch {
            publisher.status.collect { cameraEngine.onStreamStateChanged(it.state) }
        }

        // Live stream follows the settings: toggling it on the dashboard or in Settings starts/stops it.
        serviceScope.launch {
            preferences.settingsFlow
                .map { Triple(it.streamingEnabled, it.streamWhipUrl.trim(), it.streamBearerToken) }
                .distinctUntilChanged()
                .collect { (enabled, url, token) ->
                    if (enabled && url.isNotEmpty()) {
                        publisher.start(StreamConfig(whipUrl = url, bearerToken = token))
                        if (cameraEngine.engineState.value.isThermalThrottled) {
                            publisher.setFrameRateLimit(HOT_STREAM_FPS)
                        }
                    } else {
                        publisher.stop()
                    }
                }
        }

        // Remote control API: only listens while enabled; token changes apply without a restart.
        serviceScope.launch {
            preferences.settingsFlow
                .map { it.controlServerEnabled to it.controlPort }
                .distinctUntilChanged()
                .collect { (enabled, port) -> applyControlServer(enabled, port) }
        }

        // "Continuous Recording" in Settings takes effect immediately, not only at service start.
        serviceScope.launch {
            preferences.settingsFlow
                .map { it.continuousRecording }
                .distinctUntilChanged()
                .drop(1)
                .collect { enabled ->
                    if (enabled) cameraEngine.startContinuousRecording() else cameraEngine.stopRecording()
                }
        }

        // The "Silence siren" action must appear as soon as the siren starts.
        serviceScope.launch {
            siren.active.drop(1).collect { updateNotification() }
        }

        // Backup jobs track the connection and network settings.
        serviceScope.launch {
            preferences.settingsFlow
                .map { listOf(it.googleDriveConnected, it.autoUpload, it.wifiOnly, it.uploadIntervalMinutes) }
                .distinctUntilChanged()
                .collect {
                    val s = preferences.settingsFlow.value
                    if (s.googleDriveConnected && s.autoUpload) {
                        backupScheduler.schedulePeriodicBackup()
                    } else {
                        backupScheduler.cancelAllBackups()
                    }
                }
        }
    }

    private fun applyControlServer(enabled: Boolean, port: Int) {
        controlServer?.stop()
        controlServer = null
        if (!enabled) return

        preferences.ensureControlToken()
        val database = PerchApp.instance.database
        val backend = ServiceControlBackend(
            context = this,
            cameraEngine = cameraEngine,
            preferences = preferences,
            recordingDao = database.recordingDao(),
            alertDao = database.alertDao(),
            siren = siren,
            battery = batteryMonitor.status
        )
        val api = ControlApi(backend) { preferences.settingsFlow.value.controlToken }
        controlServer = ControlServer(api, port).also {
            if (!it.start()) Log.e(tag, "Control API could not bind to port $port")
        }
    }

    private fun startRetentionLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    retention.enforce()
                    PerchApp.instance.database.alertDao()
                        .pruneOldAlerts(System.currentTimeMillis() - ALERT_KEEP_MS)
                } catch (e: Exception) {
                    Log.e(tag, "Retention pass failed", e)
                }
                delay(RETENTION_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: ACTION_START_MONITORING

        // A START that reaches an instance which already shut down (the Activity was still bound
        // when Stop was pressed) cannot be served: the camera and scopes are gone. Let it die;
        // the next bind creates a fresh service.
        if (shutDown) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START_MONITORING -> {
                startForegroundServiceWithNotification()
                wakeLockManager.acquireWakeLock()
                startTimer()
                if (cameraEngine.engineState.value.isInitialized &&
                    preferences.settingsFlow.value.continuousRecording &&
                    !cameraEngine.engineState.value.isRecording) {
                    cameraEngine.startContinuousRecording()
                }
            }
            ACTION_STOP_MONITORING -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_STOP_SIREN -> siren.stop()
        }

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        serviceStartTime = System.currentTimeMillis()
        // On Android 14+ a declared foreground-service type whose permission is missing makes
        // startForeground throw, so only claim the microphone when it was actually granted.
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (PermissionHelper.hasAudioPermission(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildOngoingNotification(), type)
    }

    /** Refreshes the status notification; once a minute is plenty for a phone that never sleeps. */
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                updateNotification()
                delay(NOTIFICATION_REFRESH_MS)
            }
        }
    }

    private fun buildOngoingNotification(): Notification {
        val elapsedMin = (System.currentTimeMillis() - serviceStartTime) / 60_000
        val uptime = String.format(java.util.Locale.US, "%d:%02d", elapsedMin / 60, elapsedMin % 60)

        val engine = cameraEngine.engineState.value
        val details = buildList {
            add(if (engine.isRecording) "Recording" else "Monitoring")
            when (engine.streamState) {
                StreamState.LIVE -> add("streaming live")
                StreamState.CONNECTING, StreamState.RECONNECTING -> add("connecting stream")
                StreamState.IDLE -> Unit
            }
            if (engine.isThermalThrottled) add("reduced quality (phone is hot)")
        }.joinToString(" · ")

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val pendingStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, PerchApp.CHANNEL_RECORDING_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Perch is Active • up $uptime")
            .setContentText(details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)

        if (siren.active.value) {
            val silence = PendingIntent.getService(
                this, 2,
                Intent(this, RecordingService::class.java).apply { action = ACTION_STOP_SIREN },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_lock_silent_mode, "Silence siren", silence)
        }
        return builder.build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildOngoingNotification())
    }

    /** Stops everything the service runs. Idempotent: the Stop button and onDestroy both call it. */
    private fun shutdown() {
        if (shutDown) return
        shutDown = true
        Log.d(tag, "Stopping monitoring service")
        timerJob?.cancel()
        controlServer?.stop()
        controlServer = null
        siren.stop()
        // The camera engine goes first: it stops handing frames to the publisher and waits for
        // the analysis thread, so the publisher can free its native buffers safely afterwards.
        cameraEngine.release()
        publisher.release()
        wakeLockManager.releaseWakeLock()
        batteryMonitor.stop()
        serviceLifecycleOwner.stop()
        serviceScope.cancel()
        onShutdownListener?.invoke()
        onShutdownListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown()
    }

    companion object {
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_MONITORING = "com.perch.cam.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.perch.cam.action.STOP_MONITORING"
        const val ACTION_STOP_SIREN = "com.perch.cam.action.STOP_SIREN"

        private const val NOTIFICATION_REFRESH_MS = 60_000L
        private const val RETENTION_INTERVAL_MS = 10 * 60_000L
        private const val ALERT_KEEP_MS = 30L * 24 * 60 * 60 * 1000
        private const val HOT_STREAM_FPS = 10

        fun startService(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.startService(intent)
        }
    }
}
