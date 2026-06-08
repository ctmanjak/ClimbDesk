package dev.climbdesk.reservation.domain

import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.pass.domain.MemberPassStatus
import java.time.Instant

interface ReservationRepository {
    fun existsConfirmedByMemberIdAndClassSessionId(memberId: Long, classSessionId: Long): Boolean
    fun findById(reservationId: Long): ReservationSummary?
    fun findDomainById(reservationId: Long): Reservation?
    fun findPage(filters: ReservationFilters, page: Int, size: Int): ReservationSummaryPage
    fun save(reservation: Reservation): Reservation
}

data class ReservationFilters(
    val memberId: Long? = null,
    val classSessionId: Long? = null,
    val status: ReservationStatus? = null,
)

data class ReservationSummary(
    val id: Long,
    val memberId: Long,
    val classSessionId: Long,
    val memberPassId: Long,
    val status: ReservationStatus,
    val reservedAt: Instant,
    val canceledAt: Instant?,
    val cancelReason: ReservationCancelReason?,
    val classSession: ReservationClassSessionSummary,
    val memberPass: ReservationMemberPassSummary,
)

data class ReservationClassSessionSummary(
    val id: Long,
    val capacity: Int,
    val reservedCount: Int,
    val status: ClassSessionStatus,
)

data class ReservationMemberPassSummary(
    val id: Long,
    val remainingCount: Int,
    val status: MemberPassStatus,
)

data class ReservationSummaryPage(
    val items: List<ReservationSummary>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
