package dev.climbdesk.auth.domain

import java.time.Instant

data class AdminUser(
    val id: Long,
    val email: String,
    val passwordHash: String,
    val role: AdminUserRole,
    val status: AdminUserStatus,
    val createdAt: Instant? = null,
) {
    fun isActive(): Boolean = status == AdminUserStatus.ACTIVE

    companion object {
        fun create(
            email: String,
            passwordHash: String,
            role: AdminUserRole,
        ): AdminUser =
            AdminUser(
                id = 0,
                email = email,
                passwordHash = passwordHash,
                role = role,
                status = AdminUserStatus.ACTIVE,
            )
    }
}
