package dev.climbdesk.member.application

import dev.climbdesk.member.domain.Member
import dev.climbdesk.member.domain.MemberStatus
import java.time.Instant

data class CreateMemberResult(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String?,
    val status: MemberStatus,
    val createdAt: Instant,
    val deactivatedAt: Instant?,
) {
    companion object {
        fun from(member: Member): CreateMemberResult =
            CreateMemberResult(
                id = member.id,
                name = member.name,
                phone = member.phone,
                email = member.email,
                status = member.status,
                createdAt = requireNotNull(member.createdAt) { "Created member must have createdAt." },
                deactivatedAt = member.deactivatedAt,
            )
    }
}
