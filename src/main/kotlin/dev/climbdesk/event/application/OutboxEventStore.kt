package dev.climbdesk.event.application

import dev.climbdesk.event.domain.OutboxEvent
import java.time.Instant

interface OutboxEventStore {
    fun claimNext(
        now: Instant,
        maxAttempts: Int,
    ): OutboxEvent?

    fun save(event: OutboxEvent): OutboxEvent
}
