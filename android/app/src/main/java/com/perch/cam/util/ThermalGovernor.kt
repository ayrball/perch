package com.perch.cam.util

/**
 * Decides when the phone is too hot to run at full quality.
 *
 * Uses hysteresis so the camera does not flap between normal and reduced quality when the
 * temperature hovers around the threshold (each switch costs a recording-segment rollover).
 */
class ThermalGovernor(
    private val throttleAtCelsius: Float = 45f,
    private val resumeAtCelsius: Float = 41f
) {
    init {
        require(resumeAtCelsius < throttleAtCelsius) { "resume threshold must be below throttle threshold" }
    }

    @Volatile
    var isThrottled: Boolean = false
        private set

    /** Feeds a new reading; returns true when the throttled state changed. */
    @Synchronized
    fun update(temperatureCelsius: Float): Boolean {
        val next = when {
            temperatureCelsius >= throttleAtCelsius -> true
            temperatureCelsius <= resumeAtCelsius -> false
            else -> isThrottled
        }
        val changed = next != isThrottled
        isThrottled = next
        return changed
    }
}
