package dev.climbdesk.pass.presentation

import dev.climbdesk.pass.application.MAX_MEMBER_PASS_PAGE_SIZE
import dev.climbdesk.pass.application.MemberPassApplicationService
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members/{memberId}/passes")
@Validated
class MemberPassQueryController(
    private val memberPassApplicationService: MemberPassApplicationService,
) {
    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun listMemberPasses(
        @PathVariable memberId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_MEMBER_PASS_PAGE_SIZE.toLong()) size: Int,
    ): MemberPassListResponse =
        memberPassApplicationService.listMemberPasses(memberId, page, size).toResponse()
}
