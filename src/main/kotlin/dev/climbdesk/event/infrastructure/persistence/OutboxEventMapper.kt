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
        publishTarget = publishTarget,
        schemaVersion = schemaVersion,
        retryCount = retryCount,
        occurredAt = occurredAt,
        publishedAt = publishedAt,
        nextRetryAt = nextRetryAt,
        lastError = lastError,
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
        publishTarget = publishTarget,
        schemaVersion = schemaVersion,
        retryCount = retryCount,
        occurredAt = occurredAt,
        publishedAt = publishedAt,
        nextRetryAt = nextRetryAt,
        lastError = lastError,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
