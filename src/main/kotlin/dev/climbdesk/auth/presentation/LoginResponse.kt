package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.AdminUserResult
import dev.climbdesk.auth.application.LoginResult

data class LoginResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val adminUser: AdminUserResponse,
)

data class AdminUserResponse(
    val id: Long,
    val email: String,
    val role: String,
    val status: String,
)

fun LoginResult.toResponse(): LoginResponse =
    LoginResponse(
        accessToken = accessToken,
        tokenType = tokenType,
        expiresIn = expiresIn,
        adminUser = adminUser.toResponse(),
    )

private fun AdminUserResult.toResponse(): AdminUserResponse =
    AdminUserResponse(
        id = id,
        email = email,
        role = role.name,
        status = status.name,
    )
