package dev.climbdesk.member.application

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.member.domain.Member
import dev.climbdesk.member.domain.MemberRepository
import dev.climbdesk.member.domain.MemberStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class MemberApplicationServiceTest {
    @Test
    fun `create member saves active member`() {
        val repository = RecordingMemberRepository()
        val service = MemberApplicationService(repository)

        val result = service.createMember(
            CreateMemberCommand(
                name = "Hong Gil Dong",
                phone = "010-1234-5678",
                email = "hong@example.com",
            ),
        )

        assertThat(result.id).isEqualTo(10)
        assertThat(result.status).isEqualTo(MemberStatus.ACTIVE)
        assertThat(result.email).isEqualTo("hong@example.com")
        assertThat(repository.savedMember?.status).isEqualTo(MemberStatus.ACTIVE)
    }

    @Test
    fun `create member allows missing email`() {
        val repository = RecordingMemberRepository()
        val service = MemberApplicationService(repository)

        val result = service.createMember(
            CreateMemberCommand(
                name = "Hong Gil Dong",
                phone = "010-1234-5678",
                email = null,
            ),
        )

        assertThat(result.email).isNull()
        assertThat(repository.savedMember?.email).isNull()
    }

    @Test
    fun `create member rejects duplicate phone`() {
        val service = MemberApplicationService(
            RecordingMemberRepository(existingPhone = "010-1234-5678"),
        )

        assertThatThrownBy {
            service.createMember(
                CreateMemberCommand(
                    name = "Hong Gil Dong",
                    phone = "010-1234-5678",
                    email = null,
                ),
            )
        }.isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DUPLICATE_MEMBER_PHONE)
    }
}

private class RecordingMemberRepository(
    private val existingPhone: String? = null,
) : MemberRepository {
    var savedMember: Member? = null
        private set

    override fun existsByPhone(phone: String): Boolean =
        phone == existingPhone

    override fun save(member: Member): Member {
        savedMember = member
        return member.copy(id = 10, createdAt = Instant.parse("2026-05-01T01:00:00Z"))
    }
}
