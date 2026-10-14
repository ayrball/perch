package com.perch.cam.control

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.perch.cam.backup.BackupStatusStore
import com.perch.cam.camera.CameraEngine
import com.perch.cam.camera.SegmentedRecorder
import com.perch.cam.data.AlertDao
import com.perch.cam.data.AlertEvent
import com.perch.cam.data.PerchPreferences
import com.perch.cam.data.RecordingDao
import com.perch.cam.data.UploadStatus
import com.perch.cam.util.BatteryStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Bridges the control API to the live camera, siren, settings and database. */
class ServiceControlBackend(
    private val context: Context,
    private val cameraEngine: CameraEngine,
    private val preferences: PerchPreferences,
    private val recordingDao: RecordingDao,
    private val alertDao: AlertDao,
    private val siren: SirenPlayer,
    private val battery: StateFlow<BatteryStatus>
) : ControlBackend {

    private val backupStore = BackupStatusStore.get(context)
    private val recordingDir = SegmentedRecorder.recordingDirectory(context)

    // Counting rows on every 2 s poll is wasteful on a phone that never sleeps; cache briefly.
    private data class DbCounts(val usedBytes: Long, val pending: Int, val uploaded: Int, val at: Long)

    @Volatile
    private var cachedCounts: DbCounts? = null

    override suspend fun status(): StatusSnapshot {
        val engine = cameraEngine.engineState.value
        val settings = preferences.settingsFlow.value
        val bat = battery.value
        val backup = backupStore.status.value
        val counts = counts()

        val backupState = when {
            !settings.googleDriveConnected -> "disconnected"
            backup.needsAuth -> "needs_auth"
            else -> "connected"
        }

        return StatusSnapshot(
            deviceName = Build.MODEL ?: "Android phone",
            timestamp = System.currentTimeMillis(),
            recording = engine.isRecording,
            recordingSeconds = engine.currentRecordingSeconds,
            segmentMinutes = settings.segmentDurationMinutes,
            streaming = engine.isStreaming,
            streamState = engine.streamState.name,
            motion = engine.motionDetected,
            faceCount = engine.activeFaceCount,
            faces = engine.faces,
            flashlight = engine.isFlashlightOn,
            camera = if (engine.isFrontCamera) "front" else "back",
            videoQuality = settings.videoQuality,
            throttled = engine.isThermalThrottled,
            sirenActive = siren.active.value,
            battery = BatteryInfo(bat.levelPercentage, bat.isPluggedIn, bat.temperatureCelsius),
            storage = StorageInfo(
                usedBytes = counts.usedBytes,
                limitBytes = settings.maxStorageGb * 1024L * 1024L * 1024L,
                freeBytes = recordingDir.usableSpace
            ),
            backup = BackupInfo(
                state = backupState,
                account = settings.googleDriveAccount,
                autoUpload = settings.autoUpload,
                intervalMinutes = settings.uploadIntervalMinutes,
                pending = counts.pending,
                uploaded = counts.uploaded,
                quotaUsedBytes = backup.quotaUsedBytes,
                quotaTotalBytes = backup.quotaTotalBytes
            )
        )
    }

    private suspend fun counts(): DbCounts {
        val now = SystemClock.elapsedRealtime()
        cachedCounts?.takeIf { now - it.at < COUNTS_TTL_MS }?.let { return it }
        val fresh = DbCounts(
            usedBytes = recordingDao.getLocalRecordings().sumOf { it.fileSizeBytes },
            pending = recordingDao.countByStatus(UploadStatus.PENDING) + recordingDao.countByStatus(UploadStatus.UPLOADING),
            uploaded = recordingDao.countByStatus(UploadStatus.UPLOADED),
            at = now
        )
        cachedCounts = fresh
        return fresh
    }

    override suspend fun events(limit: Int): List<AlertEvent> = alertDao.getRecentAlerts(limit).first()

    override fun setFlashlight(on: Boolean?): Boolean =
        cameraEngine.setFlashlight(on ?: !cameraEngine.engineState.value.isFlashlightOn)

    override fun startSiren(seconds: Int) = siren.start(seconds)

    override fun stopSiren() = siren.stop()

    override fun flipCamera() = cameraEngine.switchCamera()

    override fun setQuality(quality: String) {
        preferences.updateSettings { it.copy(videoQuality = quality) }
    }

    private companion object {
        const val COUNTS_TTL_MS = 5_000L
    }
}
