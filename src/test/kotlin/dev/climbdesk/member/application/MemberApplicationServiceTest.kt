package dev.climbdesk.member.application

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.member.domain.Member
import dev.climbdesk.member.domain.MemberPage
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

    @Test
    fun `get member returns member detail`() {
        val service = MemberApplicationService(
            RecordingMemberRepository(
                members = listOf(member(id = 10, name = "Hong Gil Dong")),
            ),
        )

        val result = service.getMember(10)

        assertThat(result.id).isEqualTo(10)
        assertThat(result.name).isEqualTo("Hong Gil Dong")
        assertThat(result.status).isEqualTo(MemberStatus.ACTIVE)
    }

    @Test
    fun `get member rejects missing member`() {
        val service = MemberApplicationService(RecordingMemberRepository())

        assertThatThrownBy { service.getMember(404) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_NOT_FOUND)
    }

    @Test
    fun `list members returns paged members`() {
        val service = MemberApplicationService(
            RecordingMemberRepository(
                members = listOf(
                    member(id = 12, name = "Latest Member"),
                    member(id = 11, name = "Previous Member"),
                    member(id = 10, name = "Old Member"),
                ),
            ),
        )

        val result = service.listMembers(page = 0, size = 2)

        assertThat(result.items).extracting("id").containsExactly(12L, 11L)
        assertThat(result.page).isEqualTo(0)
        assertThat(result.size).isEqualTo(2)
        assertThat(result.totalElements).isEqualTo(3)
        assertThat(result.totalPages).isEqualTo(2)
    }

    @Test
    fun `list members rejects invalid page`() {
        val service = MemberApplicationService(RecordingMemberRepository())

        assertThatThrownBy { service.listMembers(page = -1, size = 20) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `list members rejects invalid size`() {
        val service = MemberApplicationService(RecordingMemberRepository())

        assertThatThrownBy { service.listMembers(page = 0, size = 0) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }
}

private class RecordingMemberRepository(
    private val existingPhone: String? = null,
    private val members: List<Member> = emptyList(),
) : MemberRepository {
    var savedMember: Member? = null
        private set

    override fun existsByPhone(phone: String): Boolean =
        phone == existingPhone

    override fun findById(memberId: Long): Member? =
        members.firstOrNull { it.id == memberId }

    override fun findPage(page: Int, size: Int): MemberPage {
        val sortedMembers = members.sortedByDescending { it.id }
        return MemberPage(
            items = sortedMembers.drop(page * size).take(size),
            page = page,
            size = size,
            totalElements = sortedMembers.size.toLong(),
        )
    }

    override fun save(member: Member): Member {
        savedMember = member
        return member.copy(id = 10, createdAt = Instant.parse("2026-05-01T01:00:00Z"))
    }
}

private fun member(
    id: Long,
    name: String,
): Member =
    Member(
        id = id,
        name = name,
        phone = "010-1234-${id.toString().padStart(4, '0')}",
        email = null,
        status = MemberStatus.ACTIVE,
        createdAt = Instant.parse("2026-05-01T01:00:00Z"),
    )
