package dev.climbdesk.pass.application

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.member.domain.MemberRepository
import dev.climbdesk.pass.domain.MemberPass
import dev.climbdesk.pass.domain.MemberPassRepository
import dev.climbdesk.pass.domain.PassProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberPassApplicationService(
    private val memberRepository: MemberRepository,
    private val passProductRepository: PassProductRepository,
    private val memberPassRepository: MemberPassRepository,
) {
    @Transactional
    fun issueMemberPass(command: IssueMemberPassCommand): MemberPassResult {
        val member = memberRepository.findById(command.memberId)
            ?: throw ApplicationException(ErrorCode.MEMBER_NOT_FOUND)
        member.ensureActive()

        val passProduct = passProductRepository.findById(command.passProductId)
            ?: throw ApplicationException(ErrorCode.PASS_PRODUCT_NOT_FOUND)

        val memberPass = MemberPass.issue(
            memberId = member.id,
            passProduct = passProduct,
            expiresAt = command.expiresAt,
        )

        return MemberPassResult.from(memberPassRepository.save(memberPass))
    }

    @Transactional(readOnly = true)
    fun listMemberPasses(memberId: Long, page: Int, size: Int): MemberPassPageResult {
        if (page < 0) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "page must be greater than or equal to 0.")
        }
        if (size !in 1..MAX_MEMBER_PASS_PAGE_SIZE) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "size must be between 1 and $MAX_MEMBER_PASS_PAGE_SIZE.")
        }
        if (memberRepository.findById(memberId) == null) {
            throw ApplicationException(ErrorCode.MEMBER_NOT_FOUND)
        }

        return MemberPassPageResult.from(memberPassRepository.findPageByMemberId(memberId, page, size))
    }

    @Transactional(readOnly = true)
    fun listUsageHistories(memberPassId: Long, page: Int, size: Int): PassUsageHistoryPageResult {
        if (page < 0) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "page must be greater than or equal to 0.")
        }
        if (size !in 1..MAX_MEMBER_PASS_PAGE_SIZE) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "size must be between 1 and $MAX_MEMBER_PASS_PAGE_SIZE.")
        }
        if (!memberPassRepository.existsById(memberPassId)) {
            throw ApplicationException(ErrorCode.MEMBER_PASS_NOT_FOUND)
        }

        return PassUsageHistoryPageResult.from(
            memberPassRepository.findUsageHistoryPageByMemberPassId(memberPassId, page, size),
        )
    }
}
