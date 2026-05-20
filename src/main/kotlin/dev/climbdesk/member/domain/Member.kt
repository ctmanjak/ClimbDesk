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
