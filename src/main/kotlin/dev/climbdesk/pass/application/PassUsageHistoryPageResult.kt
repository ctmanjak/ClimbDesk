package dev.climbdesk.pass.application

import dev.climbdesk.pass.domain.PassUsageHistory
import dev.climbdesk.pass.domain.PassUsageHistoryPage
import dev.climbdesk.pass.domain.PassUsageHistoryReason
import dev.climbdesk.pass.domain.PassUsageHistoryType
import java.time.Instant

data class PassUsageHistoryResult(
    val id: Long,
    val memberPassId: Long,
    val reservationId: Long,
    val type: PassUsageHistoryType,
    val reason: PassUsageHistoryReason,
    val changedCount: Int,
    val remainingCountAfter: Int,
    val createdAt: Instant,
) {
    companion object {
        fun from(history: PassUsageHistory): PassUsageHistoryResult =
            PassUsageHistoryResult(
                id = history.id,
                memberPassId = history.memberPassId,
                reservationId = history.reservationId,
                type = history.type,
                reason = history.reason,
                changedCount = history.changedCount,
                remainingCountAfter = history.remainingCountAfter,
                createdAt = requireNotNull(history.createdAt),
            )
    }
}

data class PassUsageHistoryPageResult(
    val items: List<PassUsageHistoryResult>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(historyPage: PassUsageHistoryPage): PassUsageHistoryPageResult =
            PassUsageHistoryPageResult(
                items = historyPage.items.map(PassUsageHistoryResult::from),
                page = historyPage.page,
                size = historyPage.size,
                totalElements = historyPage.totalElements,
                totalPages = if (historyPage.totalElements == 0L) {
                    0
                } else {
                    ((historyPage.totalElements - 1) / historyPage.size + 1).toInt()
                },
            )
    }
}
