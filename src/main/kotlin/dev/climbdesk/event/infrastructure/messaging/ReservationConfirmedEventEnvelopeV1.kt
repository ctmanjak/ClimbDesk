package dev.climbdesk.event.infrastructure.messaging

import java.time.Instant

data class ReservationConfirmedEventEnvelopeV1(
    val eventId: Long,
    val eventType: String,
    val schemaVersion: Int,
    val producer: String,
    val aggregateType: String,
    val aggregateId: Long,
    val occurredAt: Instant,
    val payload: ReservationConfirmedEventPayloadV1,
)

data class ReservationConfirmedEventPayloadV1(
    val reservationId: Long,
    val memberId: Long,
    val classSessionId: Long,
    val memberPassId: Long,
)
