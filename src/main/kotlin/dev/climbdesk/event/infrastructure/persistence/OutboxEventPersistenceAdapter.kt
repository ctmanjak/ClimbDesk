package dev.climbdesk.event.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.classsession.domain.ClassSessionCanceledEvent
import dev.climbdesk.event.application.OutboxEventRecorder
import dev.climbdesk.event.domain.OutboxEvent
import dev.climbdesk.reservation.domain.ReservationCanceledEvent
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class OutboxEventPersistenceAdapter(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
) : OutboxEventRecorder {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun record(event: ClassSessionCanceledEvent): OutboxEvent =
        createAndSaveOutbox(
            eventType = CLASS_SESSION_CANCELED_EVENT_TYPE,
            aggregateType = CLASS_SESSION_AGGREGATE_TYPE,
            aggregateId = event.classSessionId,
            eventPayload = event,
            occurredAt = event.occurredAt,
        )

    @Transactional(propagation = Propagation.MANDATORY)
    override fun record(event: ReservationConfirmedEvent): OutboxEvent =
        createAndSaveOutbox(
            eventType = RESERVATION_CONFIRMED_EVENT_TYPE,
            aggregateType = RESERVATION_AGGREGATE_TYPE,
            aggregateId = event.reservationId,
            eventPayload = event,
            occurredAt = event.occurredAt,
        )

    @Transactional(propagation = Propagation.MANDATORY)
    override fun record(event: ReservationCanceledEvent): OutboxEvent =
        createAndSaveOutbox(
            eventType = RESERVATION_CANCELED_EVENT_TYPE,
            aggregateType = RESERVATION_AGGREGATE_TYPE,
            aggregateId = event.reservationId,
            eventPayload = event,
            occurredAt = event.occurredAt,
        )

    private fun createAndSaveOutbox(
        eventPayload: Any,
        eventType: String,
        aggregateType: String,
        aggregateId: Long,
        occurredAt: Instant,
    ): OutboxEvent {
        val outboxEvent = OutboxEvent.pending(
            eventType = eventType,
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            payload = objectMapper.writeValueAsString(eventPayload),
            occurredAt = occurredAt,
        )

        return outboxEventJpaRepository.saveAndFlush(outboxEvent.toJpaEntity()).toDomain()
    }

    private companion object {
        const val CLASS_SESSION_CANCELED_EVENT_TYPE = "ClassSessionCanceledEvent"
        const val CLASS_SESSION_AGGREGATE_TYPE = "ClassSession"
        const val RESERVATION_CONFIRMED_EVENT_TYPE = "ReservationConfirmedEvent"
        const val RESERVATION_CANCELED_EVENT_TYPE = "ReservationCanceledEvent"
        const val RESERVATION_AGGREGATE_TYPE = "Reservation"
    }
}
