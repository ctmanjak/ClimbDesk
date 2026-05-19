package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.AuthApplicationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin-users")
class AdminUserController(
    private val authApplicationService: AuthApplicationService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    fun createAdminUser(
        @Valid @RequestBody request: CreateAdminUserRequest,
    ): CreateAdminUserResponse =
        authApplicationService.createAdminUser(request.toCommand()).toResponse()

    @PatchMapping("/{adminUserId}/role")
    @PreAuthorize("hasRole('MANAGER')")
    fun changeAdminUserRole(
        @PathVariable adminUserId: Long,
        @Valid @RequestBody request: ChangeAdminUserRoleRequest,
    ): AdminUserManagementResponse =
        authApplicationService.changeAdminUserRole(request.toCommand(adminUserId)).toResponse()

    @PatchMapping("/{adminUserId}/activate")
    @PreAuthorize("hasRole('MANAGER')")
    fun activateAdminUser(
        @PathVariable adminUserId: Long,
    ): AdminUserManagementResponse =
        authApplicationService.activateAdminUser(adminUserId).toResponse()

    @PatchMapping("/{adminUserId}/deactivate")
    @PreAuthorize("hasRole('MANAGER')")
    fun deactivateAdminUser(
        @PathVariable adminUserId: Long,
    ): AdminUserManagementResponse =
        authApplicationService.deactivateAdminUser(adminUserId).toResponse()
}
