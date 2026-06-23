package dev.climbdesk.classsession.presentation

import dev.climbdesk.classsession.application.ClassSessionApplicationService
import dev.climbdesk.classsession.application.MAX_CLASS_SESSION_PAGE_SIZE
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/class-sessions")
@Validated
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

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun listClassSessions(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_CLASS_SESSION_PAGE_SIZE.toLong()) size: Int,
    ): ClassSessionListResponse =
        classSessionApplicationService.listClassSessions(page, size).toResponse()

    @GetMapping("/{classSessionId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun getClassSession(
        @PathVariable classSessionId: Long,
    ): ClassSessionResponse =
        classSessionApplicationService.getClassSession(classSessionId).toResponse()

    @PatchMapping("/{classSessionId}/cancel")
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun cancelClassSession(
        @PathVariable classSessionId: Long,
        @Valid @RequestBody request: CancelClassSessionRequest,
    ): ClassSessionResponse =
        classSessionApplicationService.cancelClassSession(request.toCommand(classSessionId)).toResponse()
}
