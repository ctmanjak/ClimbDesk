package dev.climbdesk.pass.domain

import dev.climbdesk.common.error.DomainException
import dev.climbdesk.common.error.ErrorCode
import java.math.BigDecimal
import java.time.Instant

data class MemberPass(
    val id: Long,
    val memberId: Long,
    val passProductId: Long,
    val productNameSnapshot: String,
    val passTypeSnapshot: PassProductType,
    val totalCount: Int,
    val remainingCount: Int,
    val priceSnapshot: BigDecimal?,
    val validDaysSnapshot: Int?,
    val status: MemberPassStatus,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val version: Long = 0,
) {
    fun consume(reservationId: Long, now: Instant = Instant.now()): MemberPassUsageResult {
        if (status != MemberPassStatus.ACTIVE || remainingCount <= 0 || isExpiredAt(now)) {
            throw DomainException(ErrorCode.MEMBER_PASS_NOT_AVAILABLE)
        }

        val nextRemainingCount = remainingCount - 1
        val nextStatus = if (nextRemainingCount == 0) {
            MemberPassStatus.EXHAUSTED
        } else {
            status
        }
        val nextMemberPass = copy(
            remainingCount = nextRemainingCount,
            status = nextStatus,
        )

        return MemberPassUsageResult(
            memberPass = nextMemberPass,
            usageHistory = PassUsageHistory(
                memberPassId = id,
                reservationId = reservationId,
                type = PassUsageHistoryType.CONSUME,
                reason = PassUsageHistoryReason.RESERVATION_CONFIRMED,
                changedCount = -1,
                remainingCountAfter = nextRemainingCount,
            ),
        )
    }

    fun restore(
        reservationId: Long,
        reason: PassUsageHistoryReason,
        now: Instant = Instant.now(),
    ): MemberPassUsageResult {
        if (status == MemberPassStatus.CANCELED || remainingCount >= totalCount) {
            throw DomainException(ErrorCode.MEMBER_PASS_RESTORE_NOT_ALLOWED)
        }

        val nextRemainingCount = remainingCount + 1
        val nextStatus = if (status == MemberPassStatus.EXHAUSTED && !isExpiredAt(now)) {
            MemberPassStatus.ACTIVE
        } else {
            status
        }
        val nextMemberPass = copy(
            remainingCount = nextRemainingCount,
            status = nextStatus,
        )

        return MemberPassUsageResult(
            memberPass = nextMemberPass,
            usageHistory = PassUsageHistory(
                memberPassId = id,
                reservationId = reservationId,
                type = PassUsageHistoryType.RESTORE,
                reason = reason,
                changedCount = 1,
                remainingCountAfter = nextRemainingCount,
            ),
        )
    }

    private fun isExpiredAt(now: Instant): Boolean =
        expiresAt != null && !expiresAt.isAfter(now)

    companion object {
        fun issue(
            memberId: Long,
            passProduct: PassProduct,
            issuedAt: Instant = Instant.now(),
            expiresAt: Instant?,
        ): MemberPass {
            if (expiresAt != null && !expiresAt.isAfter(issuedAt)) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "expiresAt must be after issuedAt.")
            }

            return MemberPass(
                id = 0,
                memberId = memberId,
                passProductId = passProduct.id,
                productNameSnapshot = passProduct.name,
                passTypeSnapshot = passProduct.type,
                totalCount = passProduct.totalCount,
                remainingCount = passProduct.totalCount,
                priceSnapshot = passProduct.price,
                validDaysSnapshot = passProduct.validDays,
                status = MemberPassStatus.ACTIVE,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
            )
        }
    }
}

data class MemberPassUsageResult(
    val memberPass: MemberPass,
    val usageHistory: PassUsageHistory,
)
