package dev.climbdesk.pass.domain

import java.time.Instant

data class PassUsageHistory(
    val id: Long = 0,
    val memberPassId: Long,
    val reservationId: Long,
    val type: PassUsageHistoryType,
    val reason: PassUsageHistoryReason,
    val changedCount: Int,
    val remainingCountAfter: Int,
    val createdAt: Instant? = null,
)

enum class PassUsageHistoryType {
    CONSUME,
    RESTORE,
}

enum class PassUsageHistoryReason {
    RESERVATION_CONFIRMED,
    RESERVATION_CANCELED,
    CLASS_SESSION_CANCELED,
}
