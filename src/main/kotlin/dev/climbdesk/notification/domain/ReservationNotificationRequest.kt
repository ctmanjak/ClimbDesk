package dev.climbdesk.notification.domain

import java.time.Instant

data class ReservationNotificationRequest(
    val sourceEventId: Long,
    val reservationId: Long,
    val memberId: Long,
    val notificationType: ReservationNotificationType,
    val status: ReservationNotificationStatus,
    val createdAt: Instant,
)

enum class ReservationNotificationType {
    RESERVATION_CONFIRMED,
}

enum class ReservationNotificationStatus {
    READY,
    SKIPPED_STALE,
}
