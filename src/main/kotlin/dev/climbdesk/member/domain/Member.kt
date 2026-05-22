package dev.climbdesk.member.domain

import dev.climbdesk.common.error.DomainException
import dev.climbdesk.common.error.ErrorCode
import java.time.Instant

data class Member(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String?,
    val status: MemberStatus,
    val createdAt: Instant? = null,
    val deactivatedAt: Instant? = null,
) {
    fun ensureActive() {
        if (status != MemberStatus.ACTIVE) {
            throw DomainException(ErrorCode.MEMBER_INACTIVE)
        }
    }

    fun deactivate(deactivatedAt: Instant = Instant.now()): Member =
        if (status == MemberStatus.INACTIVE) {
            this
        } else {
            copy(
                status = MemberStatus.INACTIVE,
                deactivatedAt = deactivatedAt,
            )
        }

    companion object {
        fun create(
            name: String,
            phone: String,
            email: String?,
        ): Member =
            Member(
                id = 0,
                name = name,
                phone = phone,
                email = email,
                status = MemberStatus.ACTIVE,
            )
    }
}
