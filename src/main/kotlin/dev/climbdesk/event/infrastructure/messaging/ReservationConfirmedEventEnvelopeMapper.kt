package dev.climbdesk.event.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.event.domain.OutboxEvent
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent
import org.springframework.stereotype.Component

@Component
class ReservationConfirmedEventEnvelopeMapper(
    private val objectMapper: ObjectMapper,
) {
    fun toEnvelope(outboxEvent: OutboxEvent): ReservationConfirmedEventEnvelopeV1 {
        require(outboxEvent.eventType == INTERNAL_EVENT_TYPE) {
            "Unsupported Outbox event type: ${outboxEvent.eventType}"
        }
        require(outboxEvent.schemaVersion == SCHEMA_VERSION) {
            "Unsupported ReservationConfirmedEvent schema version: ${outboxEvent.schemaVersion}"
        }

        val storedEvent = objectMapper.readValue(
            outboxEvent.payload,
            ReservationConfirmedEvent::class.java,
        )
        return ReservationConfirmedEventEnvelopeV1(
            eventId = outboxEvent.id,
            eventType = EXTERNAL_EVENT_TYPE,
            schemaVersion = outboxEvent.schemaVersion,
            producer = PRODUCER,
            aggregateType = outboxEvent.aggregateType,
            aggregateId = outboxEvent.aggregateId,
            occurredAt = outboxEvent.occurredAt,
            payload = ReservationConfirmedEventPayloadV1(
                reservationId = storedEvent.reservationId,
                memberId = storedEvent.memberId,
                classSessionId = storedEvent.classSessionId,
                memberPassId = storedEvent.memberPassId,
            ),
        )
    }

    private companion object {
        const val INTERNAL_EVENT_TYPE = "ReservationConfirmedEvent"
        const val EXTERNAL_EVENT_TYPE = "reservation.confirmed"
        const val PRODUCER = "climbdesk"
        const val SCHEMA_VERSION = 1
    }
}
