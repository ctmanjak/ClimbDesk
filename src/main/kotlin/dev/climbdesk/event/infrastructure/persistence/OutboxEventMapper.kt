package dev.climbdesk.event.infrastructure.persistence

import dev.climbdesk.event.domain.OutboxEvent

fun OutboxEventJpaEntity.toDomain(): OutboxEvent =
    OutboxEvent(
        id = id,
        eventType = eventType,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        payload = payload,
        status = status,
        retryCount = retryCount,
        occurredAt = occurredAt,
        publishedAt = publishedAt,
        nextRetryAt = nextRetryAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun OutboxEvent.toJpaEntity(): OutboxEventJpaEntity =
    OutboxEventJpaEntity(
        id = id,
        eventType = eventType,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        payload = payload,
        status = status,
        retryCount = retryCount,
        occurredAt = occurredAt,
        publishedAt = publishedAt,
        nextRetryAt = nextRetryAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
