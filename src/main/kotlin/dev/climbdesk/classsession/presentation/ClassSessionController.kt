package dev.climbdesk.classsession.presentation

import dev.climbdesk.classsession.application.ClassSessionApplicationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/class-sessions")
class ClassSessionController(
    private val classSessionApplicationService: ClassSessionApplicationService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun createClassSession(
        @Valid @RequestBody request: CreateClassSessionRequest,
    ): ClassSessionResponse =
        classSessionApplicationService.createClassSession(request.toCommand()).toResponse()
}
