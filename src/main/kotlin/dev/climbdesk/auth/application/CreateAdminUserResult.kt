package dev.climbdesk.auth.application

import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import java.time.Instant

data class CreateAdminUserResult(
    val id: Long,
    val email: String,
    val role: AdminUserRole,
    val status: AdminUserStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(adminUser: AdminUser): CreateAdminUserResult =
            CreateAdminUserResult(
                id = adminUser.id,
                email = adminUser.email,
                role = adminUser.role,
                status = adminUser.status,
                createdAt = requireNotNull(adminUser.createdAt) { "Created admin user must have createdAt." },
            )
    }
}
