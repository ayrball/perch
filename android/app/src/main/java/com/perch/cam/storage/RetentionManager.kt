package com.perch.cam.storage

import android.util.Log
import com.perch.cam.data.PerchPreferences
import com.perch.cam.data.RecordingDao
import com.perch.cam.data.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps the recordings folder inside the configured size budget so a 24/7 camera never fills
 * the phone. Safe to call often and from several places; runs are serialized.
 */
class RetentionManager(
    private val recordingDao: RecordingDao,
    private val preferences: PerchPreferences,
    private val recordingDir: File
) {
    private val tag = "Perch:Retention"

    companion object {
        // One lock for every instance: the service, each finished segment and the upload worker
        // all create their own RetentionManager and must not act on stale snapshots in parallel.
        private val mutex = Mutex()

        private const val BYTES_PER_GB = 1024L * 1024L * 1024L
        private const val MIN_FREE_BYTES = 1L * BYTES_PER_GB
        private val HISTORY_KEEP_MS = TimeUnit.DAYS.toMillis(90)
        private val ORPHAN_MIN_AGE_MS = TimeUnit.HOURS.toMillis(2)
    }

    data class Result(val deletedFiles: Int, val freedBytes: Long)

    suspend fun enforce(): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val settings = preferences.settingsFlow.value
            val policy = RetentionPolicy(
                limitBytes = settings.maxStorageGb.coerceAtLeast(1) * BYTES_PER_GB,
                minFreeBytes = MIN_FREE_BYTES,
                deleteAfterUpload = settings.deleteAfterUpload && settings.googleDriveConnected
            )

            reconcileMissingFiles()

            val local = recordingDao.getLocalRecordings()
            val victims = RetentionPlanner.plan(local, recordingDir.usableSpace, policy)

            var deleted = 0
            var freed = 0L
            for (item in victims) {
                val file = File(item.filePath)
                if (file.exists() && !file.delete()) {
                    Log.w(tag, "Could not delete ${file.name}; will retry on the next pass")
                    continue
                }
                recordingDao.markLocalDeleted(item.id)
                deleted++
                freed += item.fileSizeBytes
                if (item.uploadStatus != UploadStatus.UPLOADED) {
                    Log.w(tag, "Storage limit reached: removed ${item.fileName} before it was backed up")
                }
            }

            deleteOrphanFiles(local.map { File(it.filePath).absolutePath }.toSet())
            recordingDao.deleteHistoryOlderThan(System.currentTimeMillis() - HISTORY_KEEP_MS)

            if (deleted > 0) Log.d(tag, "Retention removed $deleted files (${freed / 1024 / 1024} MB)")
            Result(deleted, freed)
        }
    }

    /** Rows that claim a local file which no longer exists (manual deletion, crash) are corrected. */
    private suspend fun reconcileMissingFiles() {
        for (item in recordingDao.getLocalRecordings()) {
            if (item.uploadStatus == UploadStatus.UPLOADING) continue
            if (!File(item.filePath).exists()) recordingDao.markLocalDeleted(item.id)
        }
    }

    /** Segments left behind by a crash mid-recording are never indexed; remove them once stale. */
    private fun deleteOrphanFiles(known: Set<String>) {
        val cutoff = System.currentTimeMillis() - ORPHAN_MIN_AGE_MS
        recordingDir.listFiles { f -> f.isFile && f.extension.equals("mp4", ignoreCase = true) }
            ?.filter { it.absolutePath !in known && it.lastModified() < cutoff }
            ?.forEach {
                if (it.delete()) Log.d(tag, "Removed orphan segment ${it.name}")
            }
    }
}
