package dev.climbdesk.reservation.domain

import java.time.Instant

data class ReservationCanceledEvent(
    val reservationId: Long,
    val memberId: Long,
    val classSessionId: Long,
    val memberPassId: Long,
    val cancelReason: ReservationCancelReason,
    val occurredAt: Instant,
)
