package dev.climbdesk.event.domain

import java.time.Instant

data class OutboxEvent(
    val id: Long = 0,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: Long,
    val payload: String,
    val status: OutboxEventStatus,
    val retryCount: Int,
    val occurredAt: Instant,
    val publishedAt: Instant? = null,
    val nextRetryAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    companion object {
        fun pending(
            eventType: String,
            aggregateType: String,
            aggregateId: Long,
            payload: String,
            occurredAt: Instant,
        ): OutboxEvent =
            OutboxEvent(
                eventType = eventType,
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                payload = payload,
                status = OutboxEventStatus.PENDING,
                retryCount = 0,
                occurredAt = occurredAt,
            )
    }
}

enum class OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED,
}
