package dev.climbdesk.auth.application

import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus

data class AdminUserResult(
    val id: Long,
    val email: String,
    val role: AdminUserRole,
    val status: AdminUserStatus,
) {
    companion object {
        fun from(adminUser: AdminUser): AdminUserResult =
            AdminUserResult(
                id = adminUser.id,
                email = adminUser.email,
                role = adminUser.role,
                status = adminUser.status,
            )
    }
}
