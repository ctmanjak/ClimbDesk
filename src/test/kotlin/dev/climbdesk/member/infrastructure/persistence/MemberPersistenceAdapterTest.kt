package dev.climbdesk.member.infrastructure.persistence

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.member.domain.Member
import dev.climbdesk.member.domain.MemberStatus
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException

class MemberPersistenceAdapterTest {
    @Test
    fun `save maps member phone unique violation to duplicate member phone`() {
        val dataIntegrityViolation = DataIntegrityViolationException(
            "could not execute statement",
            constraintViolation("uk_members_phone"),
        )
        val adapter = adapterThrowing(dataIntegrityViolation)

        assertThatThrownBy { adapter.save(member()) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DUPLICATE_MEMBER_PHONE)
    }

    @Test
    fun `save does not map non-phone data integrity violation to duplicate member phone`() {
        val dataIntegrityViolation = DataIntegrityViolationException(
            "could not execute statement",
            constraintViolation("ck_members_status"),
        )
        val adapter = adapterThrowing(dataIntegrityViolation)

        assertThatThrownBy { adapter.save(member()) }
            .isSameAs(dataIntegrityViolation)
    }

    @Test
    fun `save does not map non-phone hibernate constraint violation to duplicate member phone`() {
        val constraintViolation = constraintViolation("ck_members_status")
        val adapter = adapterThrowing(constraintViolation)

        assertThatThrownBy { adapter.save(member()) }
            .isSameAs(constraintViolation)
    }

    @Test
    fun `save does not map phone check constraint violation to duplicate member phone`() {
        val constraintViolation = constraintViolation("ck_members_phone_format")
        val adapter = adapterThrowing(constraintViolation)

        assertThatThrownBy { adapter.save(member()) }
            .isSameAs(constraintViolation)
    }

    private fun adapterThrowing(exception: RuntimeException): MemberPersistenceAdapter {
        val repository = mock(MemberJpaRepository::class.java)
        `when`(repository.saveAndFlush(any(MemberJpaEntity::class.java))).thenThrow(exception)
        return MemberPersistenceAdapter(repository)
    }

    private fun constraintViolation(constraintName: String): ConstraintViolationException =
        ConstraintViolationException(
            "constraint violation",
            SQLException("constraint violation"),
            constraintName,
        )

    private fun member(): Member =
        Member(
            id = 0,
            name = "Hong Gil Dong",
            phone = "010-1234-5678",
            email = null,
            status = MemberStatus.ACTIVE,
        )
}
