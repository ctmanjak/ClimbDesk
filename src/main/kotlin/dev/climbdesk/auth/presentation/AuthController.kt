package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.AuthApplicationService
import dev.climbdesk.auth.application.LoginCommand
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authApplicationService: AuthApplicationService,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): LoginResponse =
        authApplicationService.login(
            LoginCommand(
                email = request.email,
                password = request.password,
            ),
        ).toResponse()
}
