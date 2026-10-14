package com.perch.cam.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.perch.cam.PerchApp
import com.perch.cam.camera.SegmentedRecorder
import com.perch.cam.data.PerchPreferences
import com.perch.cam.data.UploadStatus
import com.perch.cam.storage.RetentionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File as JavaFile

class DriveUploadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val tag = "Perch:DriveUploadWorker"

    companion object {
        private const val MAX_ATTEMPTS = 8

        // The immediate and the periodic worker have different unique names, so serialize them
        // here instead of letting two runs upload the same file twice.
        private val runLock = Mutex()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runLock.withLock { upload() }
    }

    private suspend fun upload(): Result {
        val recordingDao = PerchApp.instance.database.recordingDao()
        val prefs = PerchPreferences(applicationContext)
        val settings = prefs.settingsFlow.value
        val status = BackupStatusStore.get(applicationContext)

        if (!settings.autoUpload || !settings.googleDriveConnected || settings.googleDriveAccount.isBlank()) {
            Log.d(tag, "Cloud backup is off or no account is connected; skipping")
            return Result.success()
        }

        // A kill mid-upload leaves rows in UPLOADING forever; nothing else is uploading now.
        recordingDao.resetStuckUploads()

        val drive = DriveApiHelper(applicationContext)
        drive.initializeWithAccount(settings.googleDriveAccount)

        val retention = RetentionManager(recordingDao, prefs, SegmentedRecorder.recordingDirectory(applicationContext))
        val pending = recordingDao.getRecordingsByStatus(UploadStatus.PENDING)
        Log.d(tag, "Found ${pending.size} pending recordings to upload")

        var transientFailure = false

        for (item in pending) {
            val file = JavaFile(item.filePath)
            if (!item.localAvailable || !file.exists()) {
                Log.w(tag, "Local file gone for ${item.fileName}; marking failed")
                recordingDao.updateUploadStatus(item.id, UploadStatus.FAILED, null)
                continue
            }

            recordingDao.updateUploadStatus(item.id, UploadStatus.UPLOADING, null)
            when (val result = drive.uploadVideoFile(file)) {
                is DriveResult.Success -> {
                    // The file is already in Drive: record that even if WorkManager cancels this
                    // run right now, otherwise the same file would be uploaded again.
                    withContext(NonCancellable) {
                        recordingDao.updateUploadStatus(item.id, UploadStatus.UPLOADED, result.value)
                    }
                    status.recordHealthy()
                    BackupNotifier.cancel(applicationContext)
                    retention.enforce()
                }
                is DriveResult.AuthRequired -> {
                    // Retrying cannot succeed until the user signs in, so stop instead of looping.
                    recordingDao.updateUploadStatus(item.id, UploadStatus.PENDING, null)
                    status.recordNeedsAuth()
                    BackupNotifier.notifySignInRequired(applicationContext)
                    return Result.success()
                }
                is DriveResult.QuotaExceeded -> {
                    recordingDao.updateUploadStatus(item.id, UploadStatus.PENDING, null)
                    status.recordDriveFull()
                    BackupNotifier.notifyDriveFull(applicationContext)
                    return Result.success()
                }
                is DriveResult.Failed -> {
                    recordingDao.updateUploadStatus(item.id, UploadStatus.PENDING, null)
                    status.recordError(result.message)
                    transientFailure = true
                }
            }
        }

        (drive.fetchQuota() as? DriveResult.Success)?.let { status.recordQuota(it.value) }

        return when {
            !transientFailure -> Result.success()
            runAttemptCount >= MAX_ATTEMPTS -> Result.success() // the periodic job tries again later
            else -> Result.retry()
        }
    }
}
