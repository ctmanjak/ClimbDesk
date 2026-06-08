package dev.climbdesk.reservation.infrastructure.persistence

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.reservation.domain.Reservation
import dev.climbdesk.reservation.domain.ReservationClassSessionSummary
import dev.climbdesk.reservation.domain.ReservationFilters
import dev.climbdesk.reservation.domain.ReservationMemberPassSummary
import dev.climbdesk.reservation.domain.ReservationRepository
import dev.climbdesk.reservation.domain.ReservationSummary
import dev.climbdesk.reservation.domain.ReservationSummaryPage
import dev.climbdesk.reservation.domain.ReservationStatus
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.util.Locale

@Repository
class ReservationPersistenceAdapter(
    private val reservationJpaRepository: ReservationJpaRepository,
) : ReservationRepository {
    override fun existsConfirmedByMemberIdAndClassSessionId(memberId: Long, classSessionId: Long): Boolean =
        reservationJpaRepository.existsByMemberIdAndClassSessionIdAndStatus(
            memberId = memberId,
            classSessionId = classSessionId,
            status = ReservationStatus.CONFIRMED,
        )

    override fun findById(reservationId: Long): ReservationSummary? =
        reservationJpaRepository.findReservationSummaryById(reservationId)?.toSummary()

    override fun findDomainById(reservationId: Long): Reservation? =
        reservationJpaRepository.findById(reservationId).orElse(null)?.toDomain()

    override fun findDomainByIdForUpdate(reservationId: Long): Reservation? =
        reservationJpaRepository.findByIdForUpdate(reservationId)?.toDomain()

    override fun findConfirmedByClassSessionIdForUpdate(classSessionId: Long): List<Reservation> =
        reservationJpaRepository.findAllByClassSessionIdAndStatusOrderByIdAsc(
            classSessionId = classSessionId,
            status = ReservationStatus.CONFIRMED,
        )
            .map(ReservationJpaEntity::toDomain)

    override fun findPage(filters: ReservationFilters, page: Int, size: Int): ReservationSummaryPage {
        val reservationPage = reservationJpaRepository.findReservationSummaries(
            memberId = filters.memberId,
            classSessionId = filters.classSessionId,
            status = filters.status,
            pageable = PageRequest.of(page, size),
        )
        return ReservationSummaryPage(
            items = reservationPage.content.map { it.toSummary() },
            page = reservationPage.number,
            size = reservationPage.size,
            totalElements = reservationPage.totalElements,
        )
    }

    override fun save(reservation: Reservation): Reservation =
        try {
            reservationJpaRepository.saveAndFlush(reservation.toJpaEntity()).toDomain()
        } catch (exception: DataIntegrityViolationException) {
            if (exception.isConfirmedReservationUniqueViolation()) {
                throw ApplicationException(ErrorCode.DUPLICATE_RESERVATION, cause = exception)
            }
            throw exception
        } catch (exception: ConstraintViolationException) {
            if (exception.isConfirmedReservationUniqueViolation()) {
                throw ApplicationException(ErrorCode.DUPLICATE_RESERVATION, cause = exception)
            }
            throw exception
        }

    private fun DataIntegrityViolationException.isConfirmedReservationUniqueViolation(): Boolean =
        causeChain().any { cause ->
            cause is ConstraintViolationException && cause.isConfirmedReservationUniqueViolation()
        } || causeChain().any { cause ->
            cause.message?.containsConfirmedReservationUniqueConstraint() == true
        }

    private fun ConstraintViolationException.isConfirmedReservationUniqueViolation(): Boolean =
        constraintName?.containsConfirmedReservationUniqueConstraint() == true ||
            message?.containsConfirmedReservationUniqueConstraint() == true

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private fun ReservationSummaryProjection.toSummary(): ReservationSummary =
        ReservationSummary(
            id = id,
            memberId = memberId,
            classSessionId = classSessionId,
            memberPassId = memberPassId,
            status = status,
            reservedAt = reservedAt,
            canceledAt = canceledAt,
            cancelReason = cancelReason,
            classSession = ReservationClassSessionSummary(
                id = classSessionSummaryId,
                capacity = classSessionCapacity,
                reservedCount = classSessionReservedCount,
                status = classSessionStatus,
            ),
            memberPass = ReservationMemberPassSummary(
                id = memberPassSummaryId,
                remainingCount = memberPassRemainingCount,
                status = memberPassStatus,
            ),
        )

    private fun String.containsConfirmedReservationUniqueConstraint(): Boolean {
        val normalized = lowercase(Locale.ROOT)
        return normalized.contains(CONFIRMED_RESERVATION_UNIQUE_INDEX) ||
            (
                normalized.contains(RESERVATIONS_TABLE_NAME) &&
                    normalized.contains(MEMBER_ID_COLUMN_NAME) &&
                    normalized.contains(CLASS_SESSION_ID_COLUMN_NAME) &&
                    normalized.contains(UNIQUE_VIOLATION_MARKER)
                )
    }

    private companion object {
        const val CONFIRMED_RESERVATION_UNIQUE_INDEX = "uk_reservations_confirmed_member_class"
        const val RESERVATIONS_TABLE_NAME = "reservations"
        const val MEMBER_ID_COLUMN_NAME = "member_id"
        const val CLASS_SESSION_ID_COLUMN_NAME = "class_session_id"
        const val UNIQUE_VIOLATION_MARKER = "unique"
    }
}
