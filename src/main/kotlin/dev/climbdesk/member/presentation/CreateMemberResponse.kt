package dev.climbdesk.member.presentation

import dev.climbdesk.member.application.CreateMemberResult
import java.time.Instant

data class CreateMemberResponse(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String?,
    val status: String,
    val createdAt: Instant,
    val deactivatedAt: Instant?,
)

fun CreateMemberResult.toResponse(): CreateMemberResponse =
    CreateMemberResponse(
        id = id,
        name = name,
        phone = phone,
        email = email,
        status = status.name,
        createdAt = createdAt,
        deactivatedAt = deactivatedAt,
    )
