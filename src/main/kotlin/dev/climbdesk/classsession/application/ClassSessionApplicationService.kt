package dev.climbdesk.classsession.application

import dev.climbdesk.classsession.domain.ClassSession
import dev.climbdesk.classsession.domain.ClassSessionCanceledEvent
import dev.climbdesk.classsession.domain.ClassSessionRepository
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.event.application.OutboxEventRecorder
import dev.climbdesk.pass.domain.MemberPassRepository
import dev.climbdesk.pass.domain.PassUsageHistoryReason
import dev.climbdesk.reservation.domain.ReservationCancelReason
import dev.climbdesk.reservation.domain.ReservationRepository
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ClassSessionApplicationService(
    private val classSessionRepository: ClassSessionRepository,
    private val reservationRepository: ReservationRepository,
    private val memberPassRepository: MemberPassRepository,
    private val outboxEventRecorder: OutboxEventRecorder,
) {
    @Transactional
    fun createClassSession(command: CreateClassSessionCommand): ClassSessionResult {
        val classSession = ClassSession.create(
            title = command.title,
            startsAt = command.startsAt,
            endsAt = command.endsAt,
            capacity = command.capacity,
        )

        return ClassSessionResult.from(classSessionRepository.save(classSession))
    }

    @Transactional(readOnly = true)
    fun listClassSessions(page: Int, size: Int): ClassSessionPageResult {
        if (page < 0) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "page must be greater than or equal to 0.")
        }
        if (size !in 1..MAX_CLASS_SESSION_PAGE_SIZE) {
            throw ApplicationException(
                ErrorCode.VALIDATION_FAILED,
                "size must be between 1 and $MAX_CLASS_SESSION_PAGE_SIZE.",
            )
        }

        return ClassSessionPageResult.from(classSessionRepository.findPage(page, size))
    }

    @Transactional(readOnly = true)
    fun getClassSession(classSessionId: Long): ClassSessionResult =
        classSessionRepository.findById(classSessionId)
            ?.let(ClassSessionResult::from)
            ?: throw ApplicationException(ErrorCode.CLASS_SESSION_NOT_FOUND)

    @Transactional
    fun cancelClassSession(command: CancelClassSessionCommand): ClassSessionResult {
        val now = Instant.now()
        val classSession = classSessionRepository.findByIdForUpdate(command.classSessionId)
            ?: throw ApplicationException(ErrorCode.CLASS_SESSION_NOT_FOUND)
        val confirmedReservations = reservationRepository.findConfirmedByClassSessionIdForUpdate(classSession.id)

        confirmedReservations.forEach { reservation ->
            val memberPass = memberPassRepository.findById(reservation.memberPassId)
                ?: throw ApplicationException(ErrorCode.MEMBER_PASS_NOT_FOUND)
            try {
                memberPassRepository.saveUsageResult(
                    memberPass.restore(
                        reservationId = reservation.id,
                        reason = PassUsageHistoryReason.CLASS_SESSION_CANCELED,
                        now = now,
                    ),
                )
            } catch (exception: ObjectOptimisticLockingFailureException) {
                throw ApplicationException(ErrorCode.MEMBER_PASS_VERSION_CONFLICT, cause = exception)
            }

            reservationRepository.save(
                reservation.cancel(
                    reason = ReservationCancelReason.CLASS_SESSION_CANCELED,
                    canceledAt = now,
                ),
            )
        }

        val savedClassSession = classSessionRepository.save(
            classSession.cancel(
                reason = command.reason,
                canceledAt = now,
                affectedReservationCount = confirmedReservations.size,
            ),
        )
        outboxEventRecorder.record(
            ClassSessionCanceledEvent(
                classSessionId = savedClassSession.id,
                cancelReason = requireNotNull(savedClassSession.cancelReason),
                affectedReservationCount = savedClassSession.affectedReservationCount,
                occurredAt = now,
            ),
        )

        return ClassSessionResult.from(savedClassSession)
    }
}
