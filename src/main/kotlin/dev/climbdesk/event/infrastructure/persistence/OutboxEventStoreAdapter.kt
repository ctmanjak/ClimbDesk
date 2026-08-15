package dev.climbdesk.event.infrastructure.persistence

import dev.climbdesk.event.application.OutboxEventStore
import dev.climbdesk.event.domain.OutboxEvent
import java.time.Instant

class OutboxEventStoreAdapter(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
) : OutboxEventStore {
    override fun claimNext(
        now: Instant,
        maxAttempts: Int,
    ): OutboxEvent? = outboxEventJpaRepository.claimNextPublishable(now, maxAttempts)?.toDomain()

    override fun save(event: OutboxEvent): OutboxEvent =
        outboxEventJpaRepository.saveAndFlush(event.toJpaEntity()).toDomain()
}
