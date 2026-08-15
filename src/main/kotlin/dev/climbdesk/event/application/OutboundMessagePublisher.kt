package dev.climbdesk.event.application

import java.time.Duration

fun interface OutboundMessagePublisher {
    fun publish(
        message: OutboundMessage,
        confirmTimeout: Duration,
    ): PublishResult
}

sealed interface PublishResult {
    data object Success : PublishResult

    data class Failure(
        val reason: String,
    ) : PublishResult
}
