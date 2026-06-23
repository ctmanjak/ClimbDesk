package dev.climbdesk.reservation.application

data class CreateReservationCommand(
    val memberId: Long,
    val classSessionId: Long,
)
