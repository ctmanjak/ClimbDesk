package dev.climbdesk.pass.presentation

import dev.climbdesk.pass.application.MemberPassResult
import dev.climbdesk.pass.domain.MemberPassStatus
import java.time.Instant

data class MemberPassResponse(
    val id: Long,
    val memberId: Long,
    val passProductId: Long,
    val productNameSnapshot: String,
    val totalCount: Int,
    val remainingCount: Int,
    val status: MemberPassStatus,
    val issuedAt: Instant,
    val expiresAt: Instant?,
)

fun MemberPassResult.toResponse(): MemberPassResponse =
    MemberPassResponse(
        id = id,
        memberId = memberId,
        passProductId = passProductId,
        productNameSnapshot = productNameSnapshot,
        totalCount = totalCount,
        remainingCount = remainingCount,
        status = status,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
    )
