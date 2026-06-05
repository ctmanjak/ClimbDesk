package dev.climbdesk.reservation.presentation

import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.reservation.application.ReservationClassSessionResult
import dev.climbdesk.reservation.application.ReservationMemberPassResult
import dev.climbdesk.reservation.application.ReservationResult
import dev.climbdesk.reservation.domain.ReservationCancelReason
import dev.climbdesk.reservation.domain.ReservationStatus
import java.time.Instant

data class ReservationResponse(
    val id: Long,
    val memberId: Long,
    val classSessionId: Long,
    val memberPassId: Long,
    val status: ReservationStatus,
    val reservedAt: Instant,
    val canceledAt: Instant?,
    val cancelReason: ReservationCancelReason?,
    val classSession: ReservationClassSessionResponse,
    val memberPass: ReservationMemberPassResponse,
)

data class ReservationClassSessionResponse(
    val id: Long,
    val capacity: Int,
    val reservedCount: Int,
    val status: ClassSessionStatus,
)

data class ReservationMemberPassResponse(
    val id: Long,
    val remainingCount: Int,
    val status: MemberPassStatus,
)

fun ReservationResult.toResponse(): ReservationResponse =
    ReservationResponse(
        id = id,
        memberId = memberId,
        classSessionId = classSessionId,
        memberPassId = memberPassId,
        status = status,
        reservedAt = reservedAt,
        canceledAt = canceledAt,
        cancelReason = cancelReason,
        classSession = classSession.toResponse(),
        memberPass = memberPass.toResponse(),
    )

private fun ReservationClassSessionResult.toResponse(): ReservationClassSessionResponse =
    ReservationClassSessionResponse(
        id = id,
        capacity = capacity,
        reservedCount = reservedCount,
        status = status,
    )

private fun ReservationMemberPassResult.toResponse(): ReservationMemberPassResponse =
    ReservationMemberPassResponse(
        id = id,
        remainingCount = remainingCount,
        status = status,
    )
