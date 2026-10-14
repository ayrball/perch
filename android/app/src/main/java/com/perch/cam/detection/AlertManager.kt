package com.perch.cam.detection

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.perch.cam.MainActivity
import com.perch.cam.PerchApp
import com.perch.cam.R
import com.perch.cam.data.AlertDao
import com.perch.cam.data.AlertEvent
import com.perch.cam.data.AlertType
import com.perch.cam.data.PerchPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlertManager(
    private val context: Context,
    private val alertDao: AlertDao,
    private val preferences: PerchPreferences = PerchPreferences(context)
) {
    private val tag = "Perch:AlertManager"
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cooldown maps to prevent notification spam
    private var lastMotionAlertTime = 0L
    private var lastFaceAlertTime = 0L
    private val alertCooldownMs = 30_000L // 30 seconds cooldown per type

    fun onMotionDetected(changedRatio: Float) {
        val now = System.currentTimeMillis()
        if (now - lastMotionAlertTime < alertCooldownMs) return
        lastMotionAlertTime = now

        Log.d(tag, "Motion alert triggered: ratio=$changedRatio")

        val event = AlertEvent(
            type = AlertType.MOTION,
            title = "Motion Detected",
            description = "Significant movement detected in monitored area",
            timestamp = now,
            confidence = (changedRatio * 10).coerceAtMost(1.0f)
        )

        coroutineScope.launch { saveAlert(event) }

        sendNotification(
            notificationId = 1001,
            title = "⚠️ Motion Detected",
            message = "Perch detected movement in your camera zone."
        )
    }

    fun onFaceDetected(faceCount: Int) {
        val now = System.currentTimeMillis()
        if (now - lastFaceAlertTime < alertCooldownMs) return
        lastFaceAlertTime = now

        Log.d(tag, "Face alert triggered: count=$faceCount")

        val title = if (faceCount == 1) "Person Detected" else "$faceCount People Detected"
        val desc = if (faceCount == 1) "A person was spotted in frame" else "Multiple people detected in frame"

        val event = AlertEvent(
            type = AlertType.FACE_STRANGER,
            title = title,
            description = desc,
            timestamp = now,
            faceCount = faceCount,
            confidence = 0.95f
        )

        coroutineScope.launch { saveAlert(event) }

        sendNotification(
            notificationId = 1002,
            title = "👤 $title",
            message = "Perch camera identified $desc."
        )
    }

    /** A full disk or locked database must never take the camera service down with it. */
    private suspend fun saveAlert(event: AlertEvent) {
        try {
            alertDao.insertAlert(event)
        } catch (e: Exception) {
            Log.e(tag, "Could not save alert", e)
        }
    }

    private fun sendNotification(notificationId: Int, title: String, message: String) {
        // Events are always recorded; the setting only controls the phone's own heads-up alerts.
        if (!preferences.settingsFlow.value.pushNotificationsEnabled) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, PerchApp.CHANNEL_ALERTS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
