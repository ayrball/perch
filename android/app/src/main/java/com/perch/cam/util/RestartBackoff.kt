package com.perch.cam.util

/**
 * Delay before retrying something that keeps failing right away (e.g. a camera another app is
 * holding): 1 s, 2 s, 4 s ... capped at [maxMs]. Without it such a failure becomes a tight loop
 * that burns CPU and fills the disk with empty files.
 */
class RestartBackoff(private val baseMs: Long = 1_000L, private val maxMs: Long = 30_000L) {
    private var failures = 0

    /** Records a failure and returns how long to wait before the next attempt. */
    @Synchronized
    fun onFailure(): Long {
        failures++
        return minOf(baseMs shl (failures - 1).coerceAtMost(20), maxMs)
    }

    @Synchronized
    fun onSuccess() {
        failures = 0
    }
}
