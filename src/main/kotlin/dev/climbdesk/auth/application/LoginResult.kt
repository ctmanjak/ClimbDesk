package dev.climbdesk.auth.application

data class LoginResult(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val adminUser: AdminUserResult,
)
