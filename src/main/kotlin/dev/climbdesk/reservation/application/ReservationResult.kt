package dev.climbdesk.reservation.application

import dev.climbdesk.classsession.domain.ClassSession
import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.pass.domain.MemberPass
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.reservation.domain.Reservation
import dev.climbdesk.reservation.domain.ReservationCancelReason
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
    }
}
