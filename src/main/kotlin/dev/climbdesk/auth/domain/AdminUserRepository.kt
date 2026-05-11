package dev.climbdesk.auth.domain

interface AdminUserRepository {
    fun findByEmail(email: String): AdminUser?
}
