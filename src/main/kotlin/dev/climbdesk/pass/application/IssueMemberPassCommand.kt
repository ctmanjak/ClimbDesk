package dev.climbdesk.pass.application

import java.time.Instant

data class IssueMemberPassCommand(
    val memberId: Long,
    val passProductId: Long,
    val expiresAt: Instant?,
)
