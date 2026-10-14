package com.perch.cam.backup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI and the control API show about cloud backup. Never contains secrets. */
data class BackupStatus(
    val needsAuth: Boolean = false,
    val driveFull: Boolean = false,
    val quotaUsedBytes: Long? = null,
    val quotaTotalBytes: Long? = null,
    val lastSyncMillis: Long = 0L,
    val lastError: String? = null
)

/**
 * Persists the outcome of the last backup run so Settings and the web viewer can show real
 * quota and sign-in state instead of placeholders.
 */
class BackupStatusStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("perch_backup_status", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(load())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    companion object {
        @Volatile
        private var instance: BackupStatusStore? = null

        fun get(context: Context): BackupStatusStore =
            instance ?: synchronized(this) {
                instance ?: BackupStatusStore(context).also { instance = it }
            }
    }

    private fun load() = BackupStatus(
        needsAuth = prefs.getBoolean("needs_auth", false),
        driveFull = prefs.getBoolean("drive_full", false),
        quotaUsedBytes = prefs.takeIf { it.contains("quota_used") }?.getLong("quota_used", 0L),
        quotaTotalBytes = prefs.takeIf { it.contains("quota_total") }?.getLong("quota_total", 0L),
        lastSyncMillis = prefs.getLong("last_sync", 0L),
        lastError = prefs.getString("last_error", null)
    )

    @Synchronized
    fun update(change: (BackupStatus) -> BackupStatus) {
        val next = change(_status.value)
        prefs.edit().apply {
            putBoolean("needs_auth", next.needsAuth)
            putBoolean("drive_full", next.driveFull)
            if (next.quotaUsedBytes != null) putLong("quota_used", next.quotaUsedBytes) else remove("quota_used")
            if (next.quotaTotalBytes != null) putLong("quota_total", next.quotaTotalBytes) else remove("quota_total")
            putLong("last_sync", next.lastSyncMillis)
            putString("last_error", next.lastError)
        }.apply()
        _status.value = next
    }

    fun recordQuota(quota: DriveQuota?) = update {
        it.copy(quotaUsedBytes = quota?.usedBytes, quotaTotalBytes = quota?.totalBytes)
    }

    /** Called after a successful Drive round trip: clears every error flag. */
    fun recordHealthy() = update {
        it.copy(needsAuth = false, driveFull = false, lastError = null, lastSyncMillis = System.currentTimeMillis())
    }

    fun recordNeedsAuth() = update { it.copy(needsAuth = true, lastError = "Sign-in required") }

    fun recordDriveFull() = update { it.copy(driveFull = true, lastError = "Google Drive is full") }

    fun recordError(message: String) = update { it.copy(lastError = message) }

    /** Forget everything when the user disconnects the account. */
    fun clear() = update { BackupStatus() }
}
