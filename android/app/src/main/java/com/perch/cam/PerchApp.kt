package com.perch.cam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import com.perch.cam.data.PerchDatabase

class PerchApp : Application(), Configuration.Provider {

    companion object {
        const val CHANNEL_RECORDING_ID = "perch_recording_channel"
        const val CHANNEL_ALERTS_ID = "perch_alerts_channel"
        lateinit var instance: PerchApp
            private set
    }

    val database: PerchDatabase by lazy {
        PerchDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Foreground Service Channel (Low importance so it doesn't chime constantly)
            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING_ID,
                getString(R.string.channel_recording_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_recording_desc)
                setShowBadge(false)
            }

            // Security Alerts Channel (High importance: sound + heads-up notification)
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_alerts_desc)
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(recordingChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }
}
