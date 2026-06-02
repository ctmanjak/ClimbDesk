package dev.climbdesk.event.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.event.application.OutboxEventRecorder
import dev.climbdesk.event.domain.OutboxEvent
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class OutboxEventPersistenceAdapter(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
) : OutboxEventRecorder {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun record(event: ReservationConfirmedEvent): OutboxEvent {
        val outboxEvent = OutboxEvent.pending(
            eventType = RESERVATION_CONFIRMED_EVENT_TYPE,
            aggregateType = RESERVATION_AGGREGATE_TYPE,
            aggregateId = event.reservationId,
            payload = objectMapper.writeValueAsString(event),
            occurredAt = event.occurredAt,
        )

        return outboxEventJpaRepository.saveAndFlush(outboxEvent.toJpaEntity()).toDomain()
    }

    private companion object {
        const val RESERVATION_CONFIRMED_EVENT_TYPE = "ReservationConfirmedEvent"
        const val RESERVATION_AGGREGATE_TYPE = "Reservation"
    }
}
