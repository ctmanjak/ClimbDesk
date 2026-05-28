package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.pass.domain.MemberPass
import dev.climbdesk.pass.domain.MemberPassRepository
import org.springframework.stereotype.Repository

@Repository
class MemberPassPersistenceAdapter(
    private val memberPassJpaRepository: MemberPassJpaRepository,
) : MemberPassRepository {
    override fun save(memberPass: MemberPass): MemberPass =
        memberPassJpaRepository.saveAndFlush(memberPass.toJpaEntity()).toDomain()
}
