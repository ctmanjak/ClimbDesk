package dev.climbdesk.reservation.domain

import java.time.Instant

data class Reservation(
    val id: Long = 0,
    val memberId: Long,
    val classSessionId: Long,
    val memberPassId: Long,
    val status: ReservationStatus,
    val reservedAt: Instant,
    val canceledAt: Instant? = null,
    val cancelReason: ReservationCancelReason? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    companion object {
        fun confirm(
            memberId: Long,
            classSessionId: Long,
            memberPassId: Long,
            reservedAt: Instant = Instant.now(),
        ): Reservation =
            Reservation(
                memberId = memberId,
                classSessionId = classSessionId,
                memberPassId = memberPassId,
                status = ReservationStatus.CONFIRMED,
                reservedAt = reservedAt,
            )
    }
}
