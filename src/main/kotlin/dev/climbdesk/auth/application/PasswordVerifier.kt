package dev.climbdesk.auth.application

interface PasswordVerifier {
    fun matches(rawPassword: String, passwordHash: String): Boolean
}
