package dev.climbdesk.member.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MemberTest {
    @Test
    fun `new member defaults to active`() {
        val member = Member.create(
            name = "Hong Gil Dong",
            phone = "010-1234-5678",
            email = "hong@example.com",
        )

        assertThat(member.status).isEqualTo(MemberStatus.ACTIVE)
        assertThat(member.deactivatedAt).isNull()
    }
}
