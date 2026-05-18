package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.AuthApplicationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
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
}
