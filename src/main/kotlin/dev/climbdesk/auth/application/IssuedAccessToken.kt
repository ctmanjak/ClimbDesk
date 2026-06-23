package dev.climbdesk.auth.application

data class IssuedAccessToken(
    val token: String,
    val expiresIn: Long,
)
