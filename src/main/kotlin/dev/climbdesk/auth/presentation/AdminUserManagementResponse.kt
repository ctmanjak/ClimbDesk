package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.AdminUserManagementResult
import java.time.Instant

data class AdminUserManagementResponse(
    val id: Long,
    val email: String,
    val role: String,
    val status: String,
    val createdAt: Instant?,
)

fun AdminUserManagementResult.toResponse(): AdminUserManagementResponse =
    AdminUserManagementResponse(
        id = id,
        email = email,
        role = role.name,
        status = status.name,
        createdAt = createdAt,
    )
