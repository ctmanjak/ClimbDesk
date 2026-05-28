package dev.climbdesk.pass.presentation

import dev.climbdesk.pass.application.MemberPassApplicationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/member-passes")
class MemberPassController(
    private val memberPassApplicationService: MemberPassApplicationService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun issueMemberPass(
        @Valid @RequestBody request: IssueMemberPassRequest,
    ): MemberPassResponse =
        memberPassApplicationService.issueMemberPass(request.toCommand()).toResponse()
}
