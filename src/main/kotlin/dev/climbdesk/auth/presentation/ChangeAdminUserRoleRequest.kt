package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.ChangeAdminUserRoleCommand
import dev.climbdesk.auth.domain.AdminUserRole
import jakarta.validation.constraints.NotNull

data class ChangeAdminUserRoleRequest(
    @field:NotNull
    val role: AdminUserRole?,
) {
    fun toCommand(adminUserId: Long): ChangeAdminUserRoleCommand =
        ChangeAdminUserRoleCommand(
            adminUserId = adminUserId,
            role = requireNotNull(role),
        )
}
