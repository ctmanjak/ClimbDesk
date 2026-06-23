package dev.climbdesk.pass.presentation

import dev.climbdesk.pass.application.MemberPassPageResult
import dev.climbdesk.pass.application.MemberPassResult
import dev.climbdesk.pass.application.PassUsageHistoryPageResult
import dev.climbdesk.pass.application.PassUsageHistoryResult
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassUsageHistoryReason
import dev.climbdesk.pass.domain.PassUsageHistoryType
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

data class MemberPassListResponse(
    val items: List<MemberPassResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class PassUsageHistoryResponse(
    val id: Long,
    val memberPassId: Long,
    val reservationId: Long,
    val type: PassUsageHistoryType,
    val reason: PassUsageHistoryReason,
    val changedCount: Int,
    val remainingCountAfter: Int,
    val createdAt: Instant,
)

data class PassUsageHistoryListResponse(
    val items: List<PassUsageHistoryResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
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

fun MemberPassPageResult.toResponse(): MemberPassListResponse =
    MemberPassListResponse(
        items = items.map { it.toResponse() },
        page = page,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
    )

fun PassUsageHistoryResult.toResponse(): PassUsageHistoryResponse =
    PassUsageHistoryResponse(
        id = id,
        memberPassId = memberPassId,
        reservationId = reservationId,
        type = type,
        reason = reason,
        changedCount = changedCount,
        remainingCountAfter = remainingCountAfter,
        createdAt = createdAt,
    )

fun PassUsageHistoryPageResult.toResponse(): PassUsageHistoryListResponse =
    PassUsageHistoryListResponse(
        items = items.map { it.toResponse() },
        page = page,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
    )
