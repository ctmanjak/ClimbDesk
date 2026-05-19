package dev.climbdesk.auth.domain

interface AdminUserRepository {
    fun findById(id: Long): AdminUser?
    fun findByEmail(email: String): AdminUser?
    fun existsByEmail(email: String): Boolean
    fun countByStatusAndRole(
        status: AdminUserStatus,
        role: AdminUserRole,
    ): Long
    fun save(adminUser: AdminUser): AdminUser
}
