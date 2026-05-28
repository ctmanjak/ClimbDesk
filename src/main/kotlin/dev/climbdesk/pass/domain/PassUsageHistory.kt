package dev.climbdesk.pass.domain

data class PassUsageHistory(
    val memberPassId: Long,
    val reservationId: Long,
    val type: PassUsageHistoryType,
    val reason: PassUsageHistoryReason,
    val changedCount: Int,
    val remainingCountAfter: Int,
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
