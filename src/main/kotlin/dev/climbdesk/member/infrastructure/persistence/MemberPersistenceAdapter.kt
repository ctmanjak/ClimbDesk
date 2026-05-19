package dev.climbdesk.member.infrastructure.persistence

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.member.domain.Member
import dev.climbdesk.member.domain.MemberRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import java.util.Locale

@Repository
class MemberPersistenceAdapter(
    private val memberJpaRepository: MemberJpaRepository,
) : MemberRepository {
    override fun existsByPhone(phone: String): Boolean =
        memberJpaRepository.existsByPhone(phone)

    override fun save(member: Member): Member =
        try {
            memberJpaRepository.saveAndFlush(member.toJpaEntity()).toDomain()
        } catch (exception: DataIntegrityViolationException) {
            if (exception.isMemberPhoneUniqueViolation()) {
                throw ApplicationException(ErrorCode.DUPLICATE_MEMBER_PHONE, cause = exception)
            }
            throw exception
        } catch (exception: ConstraintViolationException) {
            if (exception.isMemberPhoneUniqueViolation()) {
                throw ApplicationException(ErrorCode.DUPLICATE_MEMBER_PHONE, cause = exception)
            }
            throw exception
        }

    private fun DataIntegrityViolationException.isMemberPhoneUniqueViolation(): Boolean =
        causeChain().any { cause ->
            cause is ConstraintViolationException && cause.isMemberPhoneUniqueViolation()
        } || causeChain().any { cause ->
            cause.message?.containsMemberPhoneConstraint() == true
        }

    private fun ConstraintViolationException.isMemberPhoneUniqueViolation(): Boolean =
        constraintName?.containsMemberPhoneConstraint() == true ||
            message?.containsMemberPhoneConstraint() == true

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private fun String.containsMemberPhoneConstraint(): Boolean {
        val normalized = lowercase(Locale.ROOT)
        return normalized.contains(MEMBER_PHONE_CONSTRAINT_NAME) ||
            (normalized.contains("phone") && normalized.containsAny(PHONE_UNIQUE_MARKERS))
    }

    companion object {
        private const val MEMBER_PHONE_CONSTRAINT_NAME = "uk_members_phone"
        private val PHONE_UNIQUE_MARKERS = listOf("unique", "duplicate", "already exists")
    }
}

private fun String.containsAny(values: List<String>): Boolean =
    values.any { contains(it) }
