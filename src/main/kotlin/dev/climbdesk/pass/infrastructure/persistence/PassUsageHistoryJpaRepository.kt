package dev.climbdesk.pass.infrastructure.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PassUsageHistoryJpaRepository : JpaRepository<PassUsageHistoryJpaEntity, Long> {
    fun findAllByMemberPassIdOrderByCreatedAtDescIdDesc(
        memberPassId: Long,
        pageable: Pageable,
    ): Page<PassUsageHistoryJpaEntity>
}
