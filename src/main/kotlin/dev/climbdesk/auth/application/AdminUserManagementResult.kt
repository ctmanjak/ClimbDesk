package dev.climbdesk.auth.application

import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import java.time.Instant

data class AdminUserManagementResult(
    val id: Long,
    val email: String,
    val role: AdminUserRole,
    val status: AdminUserStatus,
    val createdAt: Instant?,
) {
    companion object {
        fun from(adminUser: AdminUser): AdminUserManagementResult =
            AdminUserManagementResult(
                id = adminUser.id,
                email = adminUser.email,
                role = adminUser.role,
                status = adminUser.status,
                createdAt = adminUser.createdAt,
            )
    }
}
