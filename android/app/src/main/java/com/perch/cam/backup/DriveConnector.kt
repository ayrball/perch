package com.perch.cam.backup

import android.content.Context
import android.content.Intent
import com.perch.cam.data.PerchPreferences

sealed interface ConnectOutcome {
    object Connected : ConnectOutcome

    /** Google needs the user to approve access; launch [intent] and call [DriveConnector.connect] again on OK. */
    data class NeedsConsent(val intent: Intent) : ConnectOutcome

    data class Error(val message: String) : ConnectOutcome
}

/**
 * Connects and disconnects a Google account for Drive backup. A connection is only recorded
 * after a real authenticated Drive call succeeded, so the Settings screen never claims a
 * connection that does not work.
 */
class DriveConnector(context: Context) {

    private val appContext = context.applicationContext
    private val preferences = PerchPreferences(appContext)
    private val status = BackupStatusStore.get(appContext)

    suspend fun connect(accountName: String): ConnectOutcome {
        val helper = DriveApiHelper(appContext)
        helper.initializeWithAccount(accountName)

        return when (val result = helper.verifyAccess()) {
            is DriveResult.Success -> {
                preferences.updateSettings { it.copy(googleDriveConnected = true, googleDriveAccount = accountName) }
                status.recordHealthy()
                status.recordQuota(result.value.quota)
                BackupNotifier.cancel(appContext)
                // Anything that piled up while signed out goes up now.
                val scheduler = BackupScheduler(appContext)
                scheduler.schedulePeriodicBackup()
                scheduler.scheduleImmediateBackup()
                ConnectOutcome.Connected
            }
            is DriveResult.AuthRequired ->
                result.consentIntent?.let { ConnectOutcome.NeedsConsent(it) }
                    ?: ConnectOutcome.Error("Google rejected the sign-in. Check the OAuth setup in docs/GOOGLE_DRIVE_SETUP.md.")
            is DriveResult.QuotaExceeded -> ConnectOutcome.Error("This Google account has no storage left.")
            is DriveResult.Failed -> ConnectOutcome.Error(result.message)
        }
    }

    fun disconnect() {
        preferences.updateSettings { it.copy(googleDriveConnected = false, googleDriveAccount = "") }
        BackupScheduler(appContext).cancelAllBackups()
        BackupNotifier.cancel(appContext)
        status.clear()
    }
}
