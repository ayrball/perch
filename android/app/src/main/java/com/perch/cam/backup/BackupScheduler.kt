package com.perch.cam.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.perch.cam.data.PerchPreferences
import java.util.concurrent.TimeUnit

class BackupScheduler(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val prefs = PerchPreferences(context)

    companion object {
        const val PERIODIC_WORK_TAG = "perch_periodic_drive_backup"
        const val IMMEDIATE_WORK_TAG = "perch_immediate_drive_backup"
    }

    fun scheduleImmediateBackup() {
        val settings = prefs.settingsFlow.value
        val networkType = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<DriveUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(IMMEDIATE_WORK_TAG)
            .build()

        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_TAG,
            // REPLACE would cancel an upload in progress every time the next segment finishes.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            uploadRequest
        )
    }

    fun schedulePeriodicBackup() {
        val settings = prefs.settingsFlow.value
        val networkType = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val interval = settings.uploadIntervalMinutes.toLong().coerceAtLeast(15L) // WorkManager min is 15 mins

        val periodicRequest = PeriodicWorkRequestBuilder<DriveUploadWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(PERIODIC_WORK_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    fun cancelAllBackups() {
        workManager.cancelAllWorkByTag(PERIODIC_WORK_TAG)
        workManager.cancelAllWorkByTag(IMMEDIATE_WORK_TAG)
    }
}
