package dev.climbdesk.pass.presentation

import dev.climbdesk.pass.application.MAX_MEMBER_PASS_PAGE_SIZE
import dev.climbdesk.pass.application.MemberPassApplicationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/member-passes")
@Validated
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

    @GetMapping("/{memberPassId}/usage-histories")
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun listUsageHistories(
        @PathVariable memberPassId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_MEMBER_PASS_PAGE_SIZE.toLong()) size: Int,
    ): PassUsageHistoryListResponse =
        memberPassApplicationService.listUsageHistories(memberPassId, page, size).toResponse()
}
