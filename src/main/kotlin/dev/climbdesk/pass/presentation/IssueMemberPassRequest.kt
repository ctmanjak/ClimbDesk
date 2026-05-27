package dev.climbdesk.pass.presentation

import dev.climbdesk.pass.application.IssueMemberPassCommand
import jakarta.validation.constraints.Positive
import java.time.Instant

data class IssueMemberPassRequest(
    @field:Positive
    val memberId: Long,

    @field:Positive
    val passProductId: Long,

    val expiresAt: Instant?,
) {
    fun toCommand(): IssueMemberPassCommand =
        IssueMemberPassCommand(
            memberId = memberId,
            passProductId = passProductId,
            expiresAt = expiresAt,
        )
}
