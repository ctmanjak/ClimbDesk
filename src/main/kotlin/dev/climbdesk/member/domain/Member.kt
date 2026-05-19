package dev.climbdesk.member.domain

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
