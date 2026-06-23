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

    fun isActiveManager(): Boolean =
        status == AdminUserStatus.ACTIVE && role == AdminUserRole.MANAGER

    fun changeRole(role: AdminUserRole): AdminUser =
        copy(role = role)

    fun activate(): AdminUser =
        copy(status = AdminUserStatus.ACTIVE)

    fun deactivate(): AdminUser =
        copy(status = AdminUserStatus.INACTIVE)

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
