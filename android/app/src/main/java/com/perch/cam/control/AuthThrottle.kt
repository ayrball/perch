package com.perch.cam.control

/**
 * Slows down token guessing: after [maxFailures] bad tokens from one address within
 * [windowMs], that address is refused until the window has passed.
 */
class AuthThrottle(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000L
) {
    private val failures = HashMap<String, MutableList<Long>>()

    @Synchronized
    fun isBlocked(key: String, now: Long): Boolean {
        val recent = prune(key, now)
        return recent.size >= maxFailures
    }

    @Synchronized
    fun recordFailure(key: String, now: Long) {
        prune(key, now)
        failures.getOrPut(key) { mutableListOf() }.add(now)
    }

    @Synchronized
    fun recordSuccess(key: String) {
        failures.remove(key)
    }

    private fun prune(key: String, now: Long): MutableList<Long> {
        val list = failures[key] ?: return mutableListOf()
        list.removeAll { now - it >= windowMs }
        if (list.isEmpty()) failures.remove(key)
        return list
    }
}
