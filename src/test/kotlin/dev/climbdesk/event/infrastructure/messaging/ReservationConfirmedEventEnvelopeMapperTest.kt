package dev.climbdesk.event.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.event.domain.OutboxEvent
import dev.climbdesk.event.domain.OutboxPublishTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ReservationConfirmedEventEnvelopeMapperTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val mapper = ReservationConfirmedEventEnvelopeMapper(objectMapper)

    @Test
    fun `maps version one envelope using the outbox id and an id-only payload`() {
        val occurredAt = Instant.parse("2026-08-13T08:00:00Z")
        val outboxEvent = OutboxEvent.pending(
            eventType = "ReservationConfirmedEvent",
            aggregateType = "Reservation",
            aggregateId = 987L,
            payload =
                """
                {
                  "reservationId": 987,
                  "memberId": 201,
                  "classSessionId": 301,
                  "memberPassId": 401,
                  "occurredAt": "2026-08-13T08:00:00Z"
                }
                """.trimIndent(),
            occurredAt = occurredAt,
            publishTarget = OutboxPublishTarget.RABBITMQ,
        ).copy(id = 12345L)

        val envelope = mapper.toEnvelope(outboxEvent)

        assertThat(envelope.eventId).isEqualTo(outboxEvent.id)
        assertThat(envelope.eventType).isEqualTo("reservation.confirmed")
        assertThat(envelope.schemaVersion).isEqualTo(1)
        assertThat(envelope.producer).isEqualTo("climbdesk")
        assertThat(envelope.aggregateType).isEqualTo("Reservation")
        assertThat(envelope.aggregateId).isEqualTo(987L)
        assertThat(envelope.occurredAt).isEqualTo(occurredAt)
        assertThat(envelope.payload).isEqualTo(
            ReservationConfirmedEventPayloadV1(
                reservationId = 987L,
                memberId = 201L,
                classSessionId = 301L,
                memberPassId = 401L,
            ),
        )

        val payloadFields = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(envelope.payload)
            .fieldNames()
            .asSequence()
            .toList()
        assertThat(payloadFields).containsExactlyInAnyOrder(
            "reservationId",
            "memberId",
            "classSessionId",
            "memberPassId",
        )
        assertThat(payloadFields).doesNotContain("email", "phone", "name")
    }
}
