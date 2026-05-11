package dev.climbdesk.auth.application

data class LoginCommand(
    val email: String,
    val password: String,
)
