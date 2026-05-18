package dev.climbdesk.auth.domain

interface AdminUserRepository {
    fun findByEmail(email: String): AdminUser?
    fun existsByEmail(email: String): Boolean
    fun save(adminUser: AdminUser): AdminUser
}
