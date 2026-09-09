package dev.climbdesk.notification.application

import dev.climbdesk.notification.domain.ReservationNotificationRequest
import dev.climbdesk.notification.domain.ReservationNotificationStatus
import dev.climbdesk.notification.domain.ReservationNotificationType
import dev.climbdesk.reservation.domain.Reservation
import dev.climbdesk.reservation.domain.ReservationRepository
import dev.climbdesk.reservation.domain.ReservationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ReservationConfirmedNotificationHandler(
    private val processedEventStore: ProcessedEventStore,
    private val notificationRequestStore: ReservationNotificationRequestStore,
    private val reservationRepository: ReservationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ReservationConfirmedNotificationUseCase {
    @Transactional
    override fun handle(command: ReservationConfirmedNotificationCommand): ReservationNotificationHandlingResult {
        val processedAt = clock.instant()
        val inserted = processedEventStore.insertIfAbsent(
            consumerName = CONSUMER_NAME,
            eventId = command.eventId,
            eventType = EVENT_TYPE,
            processedAt = processedAt,
        )
        if (!inserted) {
            return ReservationNotificationHandlingResult.DUPLICATE
        }

        val reservation = reservationRepository.findDomainById(command.reservationId)
            ?: throw ReservationNotificationProcessingException(
                "Reservation not found for reservation confirmed event: eventId=${command.eventId}",
            )
        validateIdentity(command, reservation)

        notificationRequestStore.save(
            ReservationNotificationRequest(
                sourceEventId = command.eventId,
                reservationId = reservation.id,
                memberId = reservation.memberId,
                notificationType = ReservationNotificationType.RESERVATION_CONFIRMED,
                status = reservation.status.toNotificationStatus(),
                createdAt = processedAt,
            ),
        )
        return ReservationNotificationHandlingResult.PROCESSED
    }

    private fun validateIdentity(
        command: ReservationConfirmedNotificationCommand,
        reservation: Reservation,
    ) {
        if (
            reservation.id != command.reservationId ||
            reservation.memberId != command.memberId ||
            reservation.classSessionId != command.classSessionId ||
            reservation.memberPassId != command.memberPassId
        ) {
            throw ReservationNotificationProcessingException(
                "Reservation identity mismatch for reservation confirmed event: eventId=${command.eventId}",
            )
        }
    }

    private fun ReservationStatus.toNotificationStatus(): ReservationNotificationStatus =
        when (this) {
            ReservationStatus.CONFIRMED -> ReservationNotificationStatus.READY
            ReservationStatus.CANCELED -> ReservationNotificationStatus.SKIPPED_STALE
        }

    companion object {
        const val CONSUMER_NAME = "reservation-notification-v1"
        const val EVENT_TYPE = "reservation.confirmed"
    }
}

class ReservationNotificationProcessingException(message: String) : RuntimeException(message)
