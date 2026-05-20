package dev.climbdesk.member.presentation

import dev.climbdesk.member.application.MemberApplicationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members")
class MemberController(
    private val memberApplicationService: MemberApplicationService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun createMember(
        @Valid @RequestBody request: CreateMemberRequest,
    ): CreateMemberResponse =
        memberApplicationService.createMember(request.toCommand()).toResponse()

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun listMembers(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): MemberListResponse =
        memberApplicationService.listMembers(page, size).toResponse()

    @GetMapping("/{memberId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun getMember(
        @PathVariable memberId: Long,
    ): MemberResponse =
        memberApplicationService.getMember(memberId).toResponse()
}
