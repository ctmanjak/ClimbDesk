package dev.climbdesk.reservation.domain

interface ReservationRepository {
    fun existsConfirmedByMemberIdAndClassSessionId(memberId: Long, classSessionId: Long): Boolean
    fun save(reservation: Reservation): Reservation
}
