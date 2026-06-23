package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.CreateAdminUserResult
import java.time.Instant

data class CreateAdminUserResponse(
    val id: Long,
    val email: String,
    val role: String,
    val status: String,
    val createdAt: Instant,
)

fun CreateAdminUserResult.toResponse(): CreateAdminUserResponse =
    CreateAdminUserResponse(
        id = id,
        email = email,
        role = role.name,
        status = status.name,
        createdAt = createdAt,
    )
