package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.CreateAdminUserCommand
import dev.climbdesk.auth.domain.AdminUserRole
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class CreateAdminUserRequest(
    @field:NotBlank
    @field:Email
    val email: String,

    @field:NotBlank
    val password: String,

    @field:NotNull
    val role: AdminUserRole?,
) {
    fun toCommand(): CreateAdminUserCommand =
        CreateAdminUserCommand(
            email = email,
            password = password,
            role = requireNotNull(role),
        )
}
