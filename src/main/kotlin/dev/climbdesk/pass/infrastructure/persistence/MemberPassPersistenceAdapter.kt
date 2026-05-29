package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.pass.domain.MemberPass
import dev.climbdesk.pass.domain.MemberPassPage
import dev.climbdesk.pass.domain.MemberPassRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class MemberPassPersistenceAdapter(
    private val memberPassJpaRepository: MemberPassJpaRepository,
) : MemberPassRepository {
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

    override fun findAvailablePassForUse(memberId: Long, now: Instant): MemberPass? =
        memberPassJpaRepository.findAvailablePassForUse(memberId, now)?.toDomain()

    override fun save(memberPass: MemberPass): MemberPass =
        memberPassJpaRepository.saveAndFlush(memberPass.toJpaEntity()).toDomain()
}
