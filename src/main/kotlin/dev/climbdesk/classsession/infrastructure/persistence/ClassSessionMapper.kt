package dev.climbdesk.classsession.infrastructure.persistence

import dev.climbdesk.classsession.domain.ClassSession

fun ClassSessionJpaEntity.toDomain(): ClassSession =
    ClassSession(
        id = id,
        title = title,
        startsAt = startsAt,
        endsAt = endsAt,
        capacity = capacity,
        reservedCount = reservedCount,
        status = status,
        createdAt = createdAt,
        canceledAt = canceledAt,
        cancelReason = cancelReason,
        affectedReservationCount = affectedReservationCount,
    )

fun ClassSession.toJpaEntity(): ClassSessionJpaEntity =
    ClassSessionJpaEntity(
        id = id,
        title = title,
        startsAt = startsAt,
        endsAt = endsAt,
        capacity = capacity,
        reservedCount = reservedCount,
        status = status,
        createdAt = createdAt,
        canceledAt = canceledAt,
        cancelReason = cancelReason,
        affectedReservationCount = affectedReservationCount,
    )
