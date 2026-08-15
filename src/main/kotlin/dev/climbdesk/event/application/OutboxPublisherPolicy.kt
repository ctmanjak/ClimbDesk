package dev.climbdesk.event.application

import java.time.Duration

data class OutboxPublisherPolicy(
    val pollInterval: Duration,
    val maxPerTick: Int,
    val maxAttempts: Int,
    val confirmTimeout: Duration,
    val retryBackoffs: List<Duration>,
) {
    init {
        require(!pollInterval.isZero && !pollInterval.isNegative) {
            "pollInterval must be positive: expected > PT0S, actual=$pollInterval"
        }
        require(maxPerTick > 0) {
            "maxPerTick must be positive: expected > 0, actual=$maxPerTick"
        }
        require(maxAttempts > 0) {
            "maxAttempts must be positive: expected > 0, actual=$maxAttempts"
        }
        require(!confirmTimeout.isZero && !confirmTimeout.isNegative) {
            "confirmTimeout must be positive: expected > PT0S, actual=$confirmTimeout"
        }
        require(retryBackoffs.size == maxAttempts - 1) {
            "retryBackoffs size must equal maxAttempts - 1: " +
                "expected=${maxAttempts - 1}, actual=${retryBackoffs.size}, maxAttempts=$maxAttempts"
        }
        require(retryBackoffs.all { !it.isZero && !it.isNegative }) {
            "retryBackoffs must contain only positive durations: expected each > PT0S, actual=$retryBackoffs"
        }
    }
}
