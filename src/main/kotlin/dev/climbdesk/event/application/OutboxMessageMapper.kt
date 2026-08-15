package dev.climbdesk.event.application

import dev.climbdesk.event.domain.OutboxEvent

fun interface OutboxMessageMapper {
    fun map(outboxEvent: OutboxEvent): OutboundMessage
}

data class OutboundMessage(
    val messageId: String,
    val eventType: String,
    val schemaVersion: Int,
    val producer: String,
    val body: ByteArray,
)

class UnsupportedOutboxContractException(
    message: String,
) : RuntimeException(message)
