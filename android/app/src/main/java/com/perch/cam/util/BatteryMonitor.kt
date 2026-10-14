package com.perch.cam.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryStatus(
    val levelPercentage: Int = 0,
    val isPluggedIn: Boolean = false,
    val temperatureCelsius: Float = 0f,
    val isOverheating: Boolean = false
)

class BatteryMonitor(
    private val context: Context,
    private val governor: ThermalGovernor = ThermalGovernor(),
    private val onStatusChanged: (BatteryStatus) -> Unit = {}
) {
    private val tag = "Perch:BatteryMonitor"
    private var isRegistered = false

    private val _status = MutableStateFlow(BatteryStatus())
    val status: StateFlow<BatteryStatus> = _status.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent != null) handle(intent)
        }
    }

    private fun handle(intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isPlugged = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f
        governor.update(tempC)

        val status = BatteryStatus(
            levelPercentage = percent,
            isPluggedIn = isPlugged,
            temperatureCelsius = tempC,
            isOverheating = governor.isThrottled
        )
        _status.value = status
        onStatusChanged(status)
    }

    fun start() {
        if (!isRegistered) {
            // ACTION_BATTERY_CHANGED is sticky: registering returns the current state immediately.
            val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            isRegistered = true
            sticky?.let { handle(it) }
            Log.d(tag, "BatteryMonitor registered")
        }
    }

    fun stop() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.e(tag, "Error unregistering battery receiver", e)
            } finally {
                isRegistered = false
            }
        }
    }
}
