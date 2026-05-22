package dev.climbdesk.member.domain

import dev.climbdesk.common.error.DomainException
import dev.climbdesk.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

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

    @Test
    fun `active member passes active eligibility validation`() {
        val member = Member.create(
            name = "Hong Gil Dong",
            phone = "010-1234-5678",
            email = "hong@example.com",
        )

        assertThatCode { member.ensureActive() }.doesNotThrowAnyException()
    }

    @Test
    fun `inactive member fails active eligibility validation`() {
        val member = Member.create(
            name = "Hong Gil Dong",
            phone = "010-1234-5678",
            email = "hong@example.com",
        ).deactivate(Instant.parse("2026-05-20T01:00:00Z"))

        assertThatThrownBy { member.ensureActive() }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_INACTIVE)
    }

    @Test
    fun `active member becomes inactive when deactivated`() {
        val deactivatedAt = Instant.parse("2026-05-20T01:00:00Z")
        val member = Member.create(
            name = "Hong Gil Dong",
            phone = "010-1234-5678",
            email = "hong@example.com",
        )

        val deactivated = member.deactivate(deactivatedAt)

        assertThat(deactivated.status).isEqualTo(MemberStatus.INACTIVE)
        assertThat(deactivated.deactivatedAt).isEqualTo(deactivatedAt)
    }

    @Test
    fun `inactive member deactivation is idempotent`() {
        val firstDeactivatedAt = Instant.parse("2026-05-20T01:00:00Z")
        val secondDeactivatedAt = Instant.parse("2026-05-21T01:00:00Z")
        val member = Member.create(
            name = "Hong Gil Dong",
            phone = "010-1234-5678",
            email = "hong@example.com",
        ).deactivate(firstDeactivatedAt)

        val deactivated = member.deactivate(secondDeactivatedAt)

        assertThat(deactivated.status).isEqualTo(MemberStatus.INACTIVE)
        assertThat(deactivated.deactivatedAt).isEqualTo(firstDeactivatedAt)
    }
}
