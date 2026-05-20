package dev.climbdesk.member.application

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.member.domain.Member
import dev.climbdesk.member.domain.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberApplicationService(
    private val memberRepository: MemberRepository,
) {
    @Transactional
    fun createMember(command: CreateMemberCommand): CreateMemberResult {
        if (memberRepository.existsByPhone(command.phone)) {
            throw ApplicationException(ErrorCode.DUPLICATE_MEMBER_PHONE)
        }

        val member = Member.create(
            name = command.name,
            phone = command.phone,
            email = command.email,
        )

        return CreateMemberResult.from(memberRepository.save(member))
    }

    @Transactional(readOnly = true)
    fun listMembers(page: Int, size: Int): MemberPageResult {
        if (page < 0) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "page must be greater than or equal to 0.")
        }
        if (size !in 1..MAX_MEMBER_PAGE_SIZE) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "size must be between 1 and $MAX_MEMBER_PAGE_SIZE.")
        }

        return MemberPageResult.from(memberRepository.findPage(page, size))
    }

    @Transactional(readOnly = true)
    fun getMember(memberId: Long): MemberResult =
        memberRepository.findById(memberId)
            ?.let(MemberResult::from)
            ?: throw ApplicationException(ErrorCode.MEMBER_NOT_FOUND)

    @Transactional
    fun deactivateMember(memberId: Long): MemberResult {
        val member = memberRepository.findById(memberId)
            ?: throw ApplicationException(ErrorCode.MEMBER_NOT_FOUND)

        return MemberResult.from(memberRepository.save(member.deactivate()))
    }
}
