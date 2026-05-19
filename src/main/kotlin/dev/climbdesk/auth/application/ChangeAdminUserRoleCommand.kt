package dev.climbdesk.auth.application

import dev.climbdesk.auth.domain.AdminUserRole

data class ChangeAdminUserRoleCommand(
    val adminUserId: Long,
    val role: AdminUserRole,
)
