package com.perch.cam.backup

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.perch.cam.MainActivity
import com.perch.cam.PerchApp
import com.perch.cam.R

/** Tells the user when cloud backup stopped because of something only they can fix. */
object BackupNotifier {

    private const val NOTIFICATION_ID = 3001

    fun notifySignInRequired(context: Context) = post(
        context,
        title = "Google Drive needs you to sign in",
        message = "Recordings are piling up on the phone. Tap to reconnect Google Drive."
    )

    fun notifyDriveFull(context: Context) = post(
        context,
        title = "Google Drive is full",
        message = "Backups are paused. Free up Drive space or old recordings will be removed from the phone."
    )

    fun cancel(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, title: String, message: String) {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, PerchApp.CHANNEL_ALERTS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }
}
