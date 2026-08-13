package dev.climbdesk.event.infrastructure.persistence

import dev.climbdesk.event.domain.OutboxEvent
import dev.climbdesk.event.domain.OutboxEventStatus
import dev.climbdesk.event.domain.OutboxPublishTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class OutboxEventMapperTest {
    @Test
    fun `domain and jpa mapping preserves event contract fields`() {
        val event = OutboxEvent(
            id = 123L,
            eventType = "ReservationConfirmedEvent",
            aggregateType = "Reservation",
            aggregateId = 987L,
            payload = "{}",
            status = OutboxEventStatus.FAILED,
            publishTarget = OutboxPublishTarget.RABBITMQ,
            schemaVersion = 1,
            retryCount = 2,
            occurredAt = Instant.parse("2026-08-13T08:00:00Z"),
            nextRetryAt = Instant.parse("2026-08-13T08:00:30Z"),
            lastError = "broker unavailable",
            createdAt = Instant.parse("2026-08-13T08:00:01Z"),
            updatedAt = Instant.parse("2026-08-13T08:00:02Z"),
        )

        assertThat(event.toJpaEntity().toDomain()).isEqualTo(event)
    }
}
