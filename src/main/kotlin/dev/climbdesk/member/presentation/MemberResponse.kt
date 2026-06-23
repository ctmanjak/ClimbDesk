package dev.climbdesk.member.presentation

import dev.climbdesk.member.application.MemberPageResult
import dev.climbdesk.member.application.MemberResult
import dev.climbdesk.member.domain.MemberStatus
import java.time.Instant

data class MemberResponse(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String?,
    val status: MemberStatus,
    val createdAt: Instant,
    val deactivatedAt: Instant?,
)

data class MemberListResponse(
    val items: List<MemberResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun MemberResult.toResponse(): MemberResponse =
    MemberResponse(
        id = id,
        name = name,
        phone = phone,
        email = email,
        status = status,
        createdAt = createdAt,
        deactivatedAt = deactivatedAt,
    )

fun MemberPageResult.toResponse(): MemberListResponse =
    MemberListResponse(
        items = items.map { it.toResponse() },
        page = page,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
    )
