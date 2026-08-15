package dev.climbdesk.event.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.event.application.OutboundMessage
import dev.climbdesk.event.application.OutboxMessageMapper
import dev.climbdesk.event.application.UnsupportedOutboxContractException
import dev.climbdesk.event.domain.OutboxEvent

class ReservationConfirmedOutboxMessageMapper(
    private val envelopeMapper: ReservationConfirmedEventEnvelopeMapper,
    private val objectMapper: ObjectMapper,
) : OutboxMessageMapper {
    override fun map(outboxEvent: OutboxEvent): OutboundMessage {
        val envelope = try {
            envelopeMapper.toEnvelope(outboxEvent)
        } catch (exception: IllegalArgumentException) {
            throw UnsupportedOutboxContractException(exception.message ?: "Unsupported Outbox contract")
        }

        return OutboundMessage(
            messageId = outboxEvent.id.toString(),
            eventType = envelope.eventType,
            schemaVersion = envelope.schemaVersion,
            producer = envelope.producer,
            body = objectMapper.writeValueAsBytes(envelope),
        )
    }
}
