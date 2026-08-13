package dev.climbdesk.notification.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "reservation_notification_requests",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_reservation_notification_source_event",
            columnNames = ["source_event_id"],
        ),
    ],
)
class ReservationNotificationRequestJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "source_event_id", nullable = false)
    val sourceEventId: Long,

    @Column(name = "reservation_id", nullable = false)
    val reservationId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 40)
    val notificationType: ReservationNotificationType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val status: ReservationNotificationStatus,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

enum class ReservationNotificationType {
    RESERVATION_CONFIRMED,
}

enum class ReservationNotificationStatus {
    READY,
    SKIPPED_STALE,
}
