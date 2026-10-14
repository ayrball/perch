package com.perch.cam.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.perch.cam.MainActivity
import com.perch.cam.PerchApp
import com.perch.cam.R
import com.perch.cam.data.PerchPreferences
import com.perch.cam.service.RecordingService
import com.perch.cam.util.PermissionHelper

class BootReceiver : BroadcastReceiver() {

    private val tag = "Perch:BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(tag, "Received broadcast action: $action")

        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == "android.intent.action.QUICKBOOT_POWERON"

        if (!isBootAction) return

        val preferences = PerchPreferences(context)
        val settings = preferences.settingsFlow.value

        if (!settings.autoStartOnBoot || !settings.continuousRecording) {
            Log.d(tag, "Auto-start on boot is disabled in settings")
            return
        }

        if (!PermissionHelper.hasAllPermissions(context)) {
            Log.w(tag, "Required permissions missing; cannot auto-start RecordingService on boot")
            return
        }

        // On Android 12+ (API 31+) and specifically Android 14+ (API 34+),
        // starting a foreground service with type camera from a broadcast receiver
        // throws ForegroundServiceStartNotAllowedException or is blocked by while-in-use camera restrictions.
        // On older Android (8.0 - 11), direct foreground service start works seamlessly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                Log.i(tag, "Starting Perch RecordingService directly on legacy Android")
                RecordingService.startService(context)
                return
            } catch (e: Exception) {
                Log.w(tag, "Direct service start failed on legacy Android", e)
            }
        }

        // For modern Android (API 31+) or as fallback, post a high-priority heads-up notification
        // allowing the user to resume monitoring with a single tap (opening MainActivity in foreground).
        notifyUserToResumeMonitoring(context)
    }

    private fun notifyUserToResumeMonitoring(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1005,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, PerchApp.CHANNEL_ALERTS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Perch Camera Ready")
            .setContentText("Tap to resume 24/7 security monitoring & protection")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(1005, notification)
    }
}
