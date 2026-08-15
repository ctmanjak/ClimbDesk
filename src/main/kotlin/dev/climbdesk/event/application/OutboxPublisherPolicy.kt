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
        require(!pollInterval.isZero && !pollInterval.isNegative)
        require(maxPerTick > 0)
        require(maxAttempts > 0)
        require(!confirmTimeout.isZero && !confirmTimeout.isNegative)
        require(retryBackoffs.size == maxAttempts - 1)
        require(retryBackoffs.all { !it.isZero && !it.isNegative })
    }
}
