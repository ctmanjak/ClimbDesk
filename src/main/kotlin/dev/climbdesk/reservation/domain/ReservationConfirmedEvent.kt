package dev.climbdesk.reservation.domain

import java.time.Instant

data class ReservationConfirmedEvent(
    val reservationId: Long,
    val memberId: Long,
    val classSessionId: Long,
    val memberPassId: Long,
    val occurredAt: Instant,
)
