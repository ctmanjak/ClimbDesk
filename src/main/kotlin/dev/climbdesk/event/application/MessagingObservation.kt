package dev.climbdesk.event.application

import java.time.Instant

interface MessagingObservation {
    fun outboxPublishFailed()

    fun consumerProcessed(occurredAt: Instant)

    fun consumerDuplicate()

    fun failureRepublishConfirmed(destination: FailureRepublishDestination)

    fun failureRepublishFailed(destination: FailureRepublishDestination)
}

enum class FailureRepublishDestination {
    RETRY,
    DLQ,
}

object NoOpMessagingObservation : MessagingObservation {
    override fun outboxPublishFailed() = Unit

    override fun consumerProcessed(occurredAt: Instant) = Unit

    override fun consumerDuplicate() = Unit

    override fun failureRepublishConfirmed(destination: FailureRepublishDestination) = Unit

    override fun failureRepublishFailed(destination: FailureRepublishDestination) = Unit
}
