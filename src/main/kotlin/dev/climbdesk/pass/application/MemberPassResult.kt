package dev.climbdesk.pass.application

import dev.climbdesk.pass.domain.MemberPass
import dev.climbdesk.pass.domain.MemberPassStatus
import java.time.Instant

data class MemberPassResult(
    val id: Long,
    val memberId: Long,
    val passProductId: Long,
    val productNameSnapshot: String,
    val totalCount: Int,
    val remainingCount: Int,
    val status: MemberPassStatus,
    val issuedAt: Instant,
    val expiresAt: Instant?,
) {
    companion object {
        fun from(memberPass: MemberPass): MemberPassResult =
            MemberPassResult(
                id = memberPass.id,
                memberId = memberPass.memberId,
                passProductId = memberPass.passProductId,
                productNameSnapshot = memberPass.productNameSnapshot,
                totalCount = memberPass.totalCount,
                remainingCount = memberPass.remainingCount,
                status = memberPass.status,
                issuedAt = memberPass.issuedAt,
                expiresAt = memberPass.expiresAt,
            )
    }
}
