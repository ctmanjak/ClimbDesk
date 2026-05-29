package dev.climbdesk.pass.infrastructure.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface MemberPassJpaRepository : JpaRepository<MemberPassJpaEntity, Long> {
    fun findAllByMemberIdOrderByIdDesc(memberId: Long, pageable: Pageable): Page<MemberPassJpaEntity>

    @Query(
        nativeQuery = true,
        value = """
            select *
            from member_passes
            where member_id = :memberId
              and status = 'ACTIVE'
              and remaining_count > 0
              and (expires_at is null or expires_at > :now)
            order by expires_at asc nulls last,
                     issued_at asc,
                     id asc
            limit 1
        """,
    )
    fun findAvailablePassForUse(
        @Param("memberId") memberId: Long,
        @Param("now") now: Instant,
    ): MemberPassJpaEntity?
}
