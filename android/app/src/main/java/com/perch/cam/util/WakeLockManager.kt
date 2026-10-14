package com.perch.cam.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

class WakeLockManager(private val context: Context) {

    private val tag = "Perch:WakeLockManager"
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Perch::ContinuousRecordingWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(tag, "CPU WakeLock acquired successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire CPU WakeLock", e)
        }

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "Perch::StreamingWifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(tag, "High-performance WiFi Lock acquired successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire WiFi Lock", e)
        }
    }

    @Synchronized
    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(tag, "CPU WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing CPU WakeLock", e)
        } finally {
            wakeLock = null
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(tag, "WiFi Lock released")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing WiFi Lock", e)
        } finally {
            wifiLock = null
        }
    }

    val isHeld: Boolean
        get() = wakeLock?.isHeld == true
}
