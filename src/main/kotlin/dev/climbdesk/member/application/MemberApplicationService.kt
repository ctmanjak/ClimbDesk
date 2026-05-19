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
}
