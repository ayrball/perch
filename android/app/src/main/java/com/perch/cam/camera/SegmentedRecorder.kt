package com.perch.cam.camera

import android.content.Context
import android.util.Log
import com.perch.cam.PerchApp
import com.perch.cam.backup.BackupScheduler
import com.perch.cam.data.PerchPreferences
import com.perch.cam.data.RecordingItem
import com.perch.cam.data.UploadStatus
import com.perch.cam.storage.RetentionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SegmentedRecorder(
    private val context: Context,
    private val backupScheduler: BackupScheduler
) {
    private val tag = "Perch:SegmentedRecorder"
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recordingDir = recordingDirectory(context)
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US) // ms: two segments can start within a second

    companion object {
        /** Where finished and in-progress mp4 segments live. */
        fun recordingDirectory(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
    }

    init {
        if (!recordingDir.exists()) {
            recordingDir.mkdirs()
        }
    }

    fun createNextRecordingFile(): File {
        val timestamp = dateFormat.format(Date())
        val fileName = "PERCH_$timestamp.mp4"
        return File(recordingDir, fileName)
    }

    fun onSegmentCompleted(
        file: File,
        durationSeconds: Long,
        hasMotion: Boolean,
        hasFace: Boolean
    ) {
        if (!file.exists() || file.length() == 0L) {
            Log.w(tag, "Segment completed but file was empty or missing: ${file.absolutePath}")
            return
        }

        val fileSize = file.length()
        val recordingItem = RecordingItem(
            filePath = file.absolutePath,
            fileName = file.name,
            durationSeconds = durationSeconds,
            fileSizeBytes = fileSize,
            uploadStatus = UploadStatus.PENDING,
            hasMotion = hasMotion,
            hasFace = hasFace
        )

        Log.d(tag, "Saving recording segment: ${file.name}, size=${fileSize / 1024 / 1024}MB, duration=${durationSeconds}s")

        coroutineScope.launch {
            // A full disk or locked database must not crash the recorder: the file stays on
            // disk, and retention only treats an unindexed file as an orphan after two hours.
            try {
                val database = PerchApp.instance.database
                database.recordingDao().insertRecording(recordingItem)

                val prefs = PerchPreferences(context)
                // Trigger immediate cloud backup worker if enabled and an account is connected
                val settings = prefs.settingsFlow.value
                if (settings.autoUpload && settings.googleDriveConnected) {
                    backupScheduler.scheduleImmediateBackup()
                }

                // Keep the phone from filling up, whether or not backup is working.
                RetentionManager(database.recordingDao(), prefs, recordingDir).enforce()
            } catch (e: Exception) {
                Log.e(tag, "Could not index segment ${file.name}", e)
            }
        }
    }

    fun getRecordingsDirectory(): File = recordingDir
}
