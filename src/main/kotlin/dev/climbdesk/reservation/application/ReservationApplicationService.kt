package dev.climbdesk.reservation.application

import dev.climbdesk.classsession.domain.ClassSessionRepository
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.event.application.OutboxEventRecorder
import dev.climbdesk.member.domain.MemberRepository
import dev.climbdesk.pass.domain.MemberPassRepository
import dev.climbdesk.reservation.domain.Reservation
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent
import dev.climbdesk.reservation.domain.ReservationFilters
import dev.climbdesk.reservation.domain.ReservationRepository
import dev.climbdesk.reservation.domain.ReservationStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ReservationApplicationService(
    private val memberRepository: MemberRepository,
    private val classSessionRepository: ClassSessionRepository,
    private val memberPassRepository: MemberPassRepository,
    private val reservationRepository: ReservationRepository,
    private val outboxEventRecorder: OutboxEventRecorder,
) {
    @Transactional
    fun reserveClass(command: CreateReservationCommand): ReservationResult {
        val now = Instant.now()
        val member = memberRepository.findById(command.memberId)
            ?: throw ApplicationException(ErrorCode.MEMBER_NOT_FOUND)
        member.ensureActive()

        val classSession = classSessionRepository.findByIdForUpdate(command.classSessionId)
            ?: throw ApplicationException(ErrorCode.CLASS_SESSION_NOT_FOUND)
        val reservedClassSession = classSession.reserveSeat()

        if (reservationRepository.existsConfirmedByMemberIdAndClassSessionId(member.id, classSession.id)) {
            throw ApplicationException(ErrorCode.DUPLICATE_RESERVATION)
        }

        val memberPass = memberPassRepository.findAvailablePassForUse(member.id, now)
            ?: throw ApplicationException(ErrorCode.MEMBER_PASS_NOT_AVAILABLE)
        val reservation = reservationRepository.save(
            Reservation.confirm(
                memberId = member.id,
                classSessionId = classSession.id,
                memberPassId = memberPass.id,
                reservedAt = now,
            ),
        )

        val usageResult = try {
            memberPassRepository.saveUsageResult(memberPass.consume(reservation.id, now))
        } catch (exception: ObjectOptimisticLockingFailureException) {
            throw ApplicationException(ErrorCode.MEMBER_PASS_VERSION_CONFLICT, cause = exception)
        }
        val savedClassSession = classSessionRepository.save(reservedClassSession)

        outboxEventRecorder.record(
            ReservationConfirmedEvent(
                reservationId = reservation.id,
                memberId = member.id,
                classSessionId = classSession.id,
                memberPassId = usageResult.memberPass.id,
                occurredAt = now,
            ),
        )

        return ReservationResult.from(
            reservation = reservation,
            classSession = savedClassSession,
            memberPass = usageResult.memberPass,
        )
    }

    @Transactional(readOnly = true)
    fun listReservations(
        page: Int,
        size: Int,
        memberId: Long?,
        classSessionId: Long?,
        status: ReservationStatus?,
    ): ReservationPageResult {
        if (page < 0) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "page must be greater than or equal to 0.")
        }
        if (size !in 1..MAX_RESERVATION_PAGE_SIZE) {
            throw ApplicationException(
                ErrorCode.VALIDATION_FAILED,
                "size must be between 1 and $MAX_RESERVATION_PAGE_SIZE.",
            )
        }

        return ReservationPageResult.from(
            reservationRepository.findPage(
                filters = ReservationFilters(
                    memberId = memberId,
                    classSessionId = classSessionId,
                    status = status,
                ),
                page = page,
                size = size,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun getReservation(reservationId: Long): ReservationResult =
        reservationRepository.findById(reservationId)
            ?.let(ReservationResult::from)
            ?: throw ApplicationException(ErrorCode.RESERVATION_NOT_FOUND)
}
