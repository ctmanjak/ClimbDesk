package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.pass.domain.MemberPass
import dev.climbdesk.pass.domain.MemberPassPage
import dev.climbdesk.pass.domain.MemberPassRepository
import dev.climbdesk.pass.domain.MemberPassUsageResult
import dev.climbdesk.pass.domain.PassUsageHistoryPage
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class MemberPassPersistenceAdapter(
    private val memberPassJpaRepository: MemberPassJpaRepository,
    private val passUsageHistoryJpaRepository: PassUsageHistoryJpaRepository,
) : MemberPassRepository {
    override fun existsById(memberPassId: Long): Boolean =
        memberPassJpaRepository.existsById(memberPassId)

    override fun findById(memberPassId: Long): MemberPass? =
        memberPassJpaRepository.findById(memberPassId).orElse(null)?.toDomain()

    override fun findPageByMemberId(memberId: Long, page: Int, size: Int): MemberPassPage {
        val memberPassPage = memberPassJpaRepository.findAllByMemberIdOrderByIdDesc(
            memberId,
            PageRequest.of(page, size),
        )
        return MemberPassPage(
            items = memberPassPage.content.map(MemberPassJpaEntity::toDomain),
            page = memberPassPage.number,
            size = memberPassPage.size,
            totalElements = memberPassPage.totalElements,
        )
    }

    override fun findUsageHistoryPageByMemberPassId(memberPassId: Long, page: Int, size: Int): PassUsageHistoryPage {
        val usageHistoryPage = passUsageHistoryJpaRepository.findAllByMemberPassIdOrderByCreatedAtDescIdDesc(
            memberPassId,
            PageRequest.of(page, size),
        )
        return PassUsageHistoryPage(
            items = usageHistoryPage.content.map(PassUsageHistoryJpaEntity::toDomain),
            page = usageHistoryPage.number,
            size = usageHistoryPage.size,
            totalElements = usageHistoryPage.totalElements,
        )
    }

    override fun findAvailablePassForUse(memberId: Long, now: Instant): MemberPass? =
        memberPassJpaRepository.findAvailablePassForUse(memberId, now)?.toDomain()

    override fun save(memberPass: MemberPass): MemberPass =
        memberPassJpaRepository.saveAndFlush(memberPass.toJpaEntity()).toDomain()

    override fun saveUsageResult(usageResult: MemberPassUsageResult): MemberPassUsageResult {
        val memberPass = memberPassJpaRepository.saveAndFlush(usageResult.memberPass.toJpaEntity()).toDomain()
        val usageHistory = passUsageHistoryJpaRepository.saveAndFlush(
            usageResult.usageHistory.toJpaEntity(),
        ).toDomain()

        return MemberPassUsageResult(
            memberPass = memberPass,
            usageHistory = usageHistory,
        )
    }
}
