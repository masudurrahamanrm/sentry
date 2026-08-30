package com.example.sentry.network

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * ReconnectionPolicy implements exponential backoff with random jitter
 * and a circuit breaker to prevent endless battery-draining reconnection loops for Sentry Agent.
 */
class ReconnectionPolicy(
    private val baseDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 60000L,
    private val maxAttemptsBeforeIdle: Int = 10
) {
    private var currentAttempt = 0

    fun getNextDelayMs(): Long {
        if (currentAttempt >= maxAttemptsBeforeIdle) {
            // Low-power idle mode: fixed 2-minute interval to preserve battery
            return 120000L
        }

        val exponentialDelay = baseDelayMs * (2.0.pow(currentAttempt.toDouble())).toLong()
        val cappedDelay = min(maxDelayMs, exponentialDelay)
        val jitter = Random.nextLong(0, (cappedDelay * 0.2).toLong().coerceAtLeast(100L))

        currentAttempt++
        return cappedDelay + jitter
    }

    fun reset() {
        currentAttempt = 0
    }

    val attemptCount: Int
        get() = currentAttempt
}
