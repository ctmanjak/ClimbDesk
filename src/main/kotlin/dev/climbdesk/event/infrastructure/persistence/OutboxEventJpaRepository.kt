package dev.climbdesk.event.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface OutboxEventJpaRepository : JpaRepository<OutboxEventJpaEntity, Long> {
    @Query(
        value = """
            select *
            from outbox_events
            where publish_target = 'RABBITMQ'
              and (
                status = 'PENDING'
                or (
                  status = 'FAILED'
                  and retry_count < :maxAttempts
                  and next_retry_at <= :now
                )
              )
            order by next_retry_at asc nulls first, id asc
            limit 1
            for update skip locked
        """,
        nativeQuery = true,
    )
    fun claimNextPublishable(
        @Param("now") now: Instant,
        @Param("maxAttempts") maxAttempts: Int,
    ): OutboxEventJpaEntity?
}
