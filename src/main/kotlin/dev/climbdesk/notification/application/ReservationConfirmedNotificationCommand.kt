package dev.climbdesk.notification.application

data class ReservationConfirmedNotificationCommand(
    val eventId: Long,
    val reservationId: Long,
    val memberId: Long,
    val classSessionId: Long,
    val memberPassId: Long,
)

interface ReservationConfirmedNotificationUseCase {
    fun handle(command: ReservationConfirmedNotificationCommand): ReservationNotificationHandlingResult
}

enum class ReservationNotificationHandlingResult {
    PROCESSED,
    DUPLICATE,
}
