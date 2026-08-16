package dev.climbdesk.notification.infrastructure.persistence

import dev.climbdesk.notification.application.ReservationNotificationRequestStore
import dev.climbdesk.notification.domain.ReservationNotificationRequest
import org.springframework.stereotype.Repository

@Repository
class ReservationNotificationRequestPersistenceAdapter(
    private val repository: ReservationNotificationRequestJpaRepository,
) : ReservationNotificationRequestStore {
    override fun save(request: ReservationNotificationRequest) {
        repository.save(
            ReservationNotificationRequestJpaEntity(
                sourceEventId = request.sourceEventId,
                reservationId = request.reservationId,
                memberId = request.memberId,
                notificationType = request.notificationType,
                status = request.status,
                createdAt = request.createdAt,
            ),
        )
    }
}
