package dev.climbdesk.auth.application

interface PasswordHasher {
    fun hash(rawPassword: String): String
}
