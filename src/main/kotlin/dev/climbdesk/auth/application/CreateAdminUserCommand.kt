package dev.climbdesk.auth.application

import dev.climbdesk.auth.domain.AdminUserRole

data class CreateAdminUserCommand(
    val email: String,
    val password: String,
    val role: AdminUserRole,
) {
    override fun toString(): String =
        "CreateAdminUserCommand(email=$email, password=***, role=$role)"
}
