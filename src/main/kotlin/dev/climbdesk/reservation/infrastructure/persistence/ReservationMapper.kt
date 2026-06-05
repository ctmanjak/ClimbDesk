package dev.climbdesk.reservation.infrastructure.persistence

import dev.climbdesk.reservation.domain.Reservation

fun ReservationJpaEntity.toDomain(): Reservation =
    Reservation(
        id = id,
        memberId = memberId,
        classSessionId = classSessionId,
        memberPassId = memberPassId,
        status = status,
        reservedAt = reservedAt,
        canceledAt = canceledAt,
        cancelReason = cancelReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun Reservation.toJpaEntity(): ReservationJpaEntity =
    ReservationJpaEntity(
        id = id,
        memberId = memberId,
        classSessionId = classSessionId,
        memberPassId = memberPassId,
        status = status,
        reservedAt = reservedAt,
        canceledAt = canceledAt,
        cancelReason = cancelReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
