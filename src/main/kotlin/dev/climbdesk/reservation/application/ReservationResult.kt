package dev.climbdesk.reservation.application

import dev.climbdesk.classsession.domain.ClassSession
import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.pass.domain.MemberPass
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.reservation.domain.Reservation
import dev.climbdesk.reservation.domain.ReservationCancelReason
import dev.climbdesk.reservation.domain.ReservationClassSessionSummary
import dev.climbdesk.reservation.domain.ReservationMemberPassSummary
import dev.climbdesk.reservation.domain.ReservationSummary
import dev.climbdesk.reservation.domain.ReservationSummaryPage
import dev.climbdesk.reservation.domain.ReservationStatus
import java.time.Instant

data class ReservationResult(
    val id: Long,
    val memberId: Long,
    val classSessionId: Long,
    val memberPassId: Long,
    val status: ReservationStatus,
    val reservedAt: Instant,
    val canceledAt: Instant?,
    val cancelReason: ReservationCancelReason?,
    val classSession: ReservationClassSessionResult,
    val memberPass: ReservationMemberPassResult,
) {
    companion object {
        fun from(
            reservation: Reservation,
            classSession: ClassSession,
            memberPass: MemberPass,
        ): ReservationResult =
            ReservationResult(
                id = reservation.id,
                memberId = reservation.memberId,
                classSessionId = reservation.classSessionId,
                memberPassId = reservation.memberPassId,
                status = reservation.status,
                reservedAt = reservation.reservedAt,
                canceledAt = reservation.canceledAt,
                cancelReason = reservation.cancelReason,
                classSession = ReservationClassSessionResult.from(classSession),
                memberPass = ReservationMemberPassResult.from(memberPass),
            )

        fun from(summary: ReservationSummary): ReservationResult =
            ReservationResult(
                id = summary.id,
                memberId = summary.memberId,
                classSessionId = summary.classSessionId,
                memberPassId = summary.memberPassId,
                status = summary.status,
                reservedAt = summary.reservedAt,
                canceledAt = summary.canceledAt,
                cancelReason = summary.cancelReason,
                classSession = ReservationClassSessionResult.from(summary.classSession),
                memberPass = ReservationMemberPassResult.from(summary.memberPass),
            )
    }
}

data class ReservationPageResult(
    val items: List<ReservationResult>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(reservationPage: ReservationSummaryPage): ReservationPageResult =
            ReservationPageResult(
                items = reservationPage.items.map(ReservationResult::from),
                page = reservationPage.page,
                size = reservationPage.size,
                totalElements = reservationPage.totalElements,
                totalPages = if (reservationPage.totalElements == 0L) {
                    0
                } else {
                    ((reservationPage.totalElements - 1) / reservationPage.size + 1).toInt()
                },
            )
    }
}

data class ReservationClassSessionResult(
    val id: Long,
    val capacity: Int,
    val reservedCount: Int,
    val status: ClassSessionStatus,
) {
    companion object {
        fun from(classSession: ClassSession): ReservationClassSessionResult =
            ReservationClassSessionResult(
                id = classSession.id,
                capacity = classSession.capacity,
                reservedCount = classSession.reservedCount,
                status = classSession.status,
            )

        fun from(classSession: ReservationClassSessionSummary): ReservationClassSessionResult =
            ReservationClassSessionResult(
                id = classSession.id,
                capacity = classSession.capacity,
                reservedCount = classSession.reservedCount,
                status = classSession.status,
            )
    }
}

data class ReservationMemberPassResult(
    val id: Long,
    val remainingCount: Int,
    val status: MemberPassStatus,
) {
    companion object {
        fun from(memberPass: MemberPass): ReservationMemberPassResult =
            ReservationMemberPassResult(
                id = memberPass.id,
                remainingCount = memberPass.remainingCount,
                status = memberPass.status,
            )

        fun from(memberPass: ReservationMemberPassSummary): ReservationMemberPassResult =
            ReservationMemberPassResult(
                id = memberPass.id,
                remainingCount = memberPass.remainingCount,
                status = memberPass.status,
            )
    }
}
