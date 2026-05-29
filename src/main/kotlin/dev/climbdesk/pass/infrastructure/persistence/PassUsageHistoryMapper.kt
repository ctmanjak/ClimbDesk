package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.pass.domain.PassUsageHistory

fun PassUsageHistoryJpaEntity.toDomain(): PassUsageHistory =
    PassUsageHistory(
        id = id,
        memberPassId = memberPassId,
        reservationId = reservationId,
        type = type,
        reason = reason,
        changedCount = changedCount,
        remainingCountAfter = remainingCountAfter,
        createdAt = createdAt,
    )

fun PassUsageHistory.toJpaEntity(): PassUsageHistoryJpaEntity =
    PassUsageHistoryJpaEntity(
        id = id,
        memberPassId = memberPassId,
        reservationId = reservationId,
        type = type,
        reason = reason,
        changedCount = changedCount,
        remainingCountAfter = remainingCountAfter,
        createdAt = createdAt,
    )
