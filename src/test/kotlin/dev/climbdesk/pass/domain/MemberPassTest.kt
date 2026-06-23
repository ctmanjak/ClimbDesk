package dev.climbdesk.pass.domain

import dev.climbdesk.common.error.DomainException
import dev.climbdesk.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class MemberPassTest {
    private val now = Instant.parse("2026-05-01T01:00:00Z")

    @Test
    fun `issued member pass copies pass product snapshot and starts active`() {
        val passProduct = PassProduct(
            id = 7,
            name = "10 Count Pass",
            type = PassProductType.COUNT_PASS,
            totalCount = 10,
            price = BigDecimal("150000"),
            validDays = 90,
        )
        val issuedAt = Instant.parse("2026-05-01T01:00:00Z")
        val expiresAt = Instant.parse("2026-08-01T14:59:59Z")

        val memberPass = MemberPass.issue(
            memberId = 3,
            passProduct = passProduct,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
        )

        assertThat(memberPass.memberId).isEqualTo(3)
        assertThat(memberPass.passProductId).isEqualTo(7)
        assertThat(memberPass.productNameSnapshot).isEqualTo("10 Count Pass")
        assertThat(memberPass.passTypeSnapshot).isEqualTo(PassProductType.COUNT_PASS)
        assertThat(memberPass.totalCount).isEqualTo(10)
        assertThat(memberPass.remainingCount).isEqualTo(10)
        assertThat(memberPass.priceSnapshot).isEqualByComparingTo("150000")
        assertThat(memberPass.validDaysSnapshot).isEqualTo(90)
        assertThat(memberPass.status).isEqualTo(MemberPassStatus.ACTIVE)
        assertThat(memberPass.issuedAt).isEqualTo(issuedAt)
        assertThat(memberPass.expiresAt).isEqualTo(expiresAt)
        assertThat(memberPass.version).isZero()
    }

    @Test
    fun `issued member pass rejects expires at not after issued at`() {
        val passProduct = PassProduct.createCountPass(
            name = "10 Count Pass",
            totalCount = 10,
            price = null,
            validDays = null,
        )
        val issuedAt = Instant.parse("2026-05-01T01:00:00Z")

        assertThatThrownBy {
            MemberPass.issue(
                memberId = 3,
                passProduct = passProduct,
                issuedAt = issuedAt,
                expiresAt = issuedAt,
            )
        }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `active member pass consume decreases remaining count and records consume history`() {
        val memberPass = memberPass(remainingCount = 2, expiresAt = now.plusSeconds(60))

        val result = memberPass.consume(reservationId = 11, now = now)

        assertThat(result.memberPass.remainingCount).isEqualTo(1)
        assertThat(result.memberPass.status).isEqualTo(MemberPassStatus.ACTIVE)
        assertThat(result.usageHistory).isEqualTo(
            PassUsageHistory(
                memberPassId = 1,
                reservationId = 11,
                type = PassUsageHistoryType.CONSUME,
                reason = PassUsageHistoryReason.RESERVATION_CONFIRMED,
                changedCount = -1,
                remainingCountAfter = 1,
            ),
        )
    }

    @Test
    fun `consume transitions to exhausted when remaining count reaches zero`() {
        val memberPass = memberPass(remainingCount = 1, expiresAt = now.plusSeconds(60))

        val result = memberPass.consume(reservationId = 11, now = now)

        assertThat(result.memberPass.remainingCount).isZero()
        assertThat(result.memberPass.status).isEqualTo(MemberPassStatus.EXHAUSTED)
    }

    @Test
    fun `consume at zero fails and never produces negative remaining count`() {
        val memberPass = memberPass(
            remainingCount = 0,
            status = MemberPassStatus.EXHAUSTED,
            expiresAt = now.plusSeconds(60),
        )

        assertThatThrownBy {
            memberPass.consume(reservationId = 11, now = now)
        }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_PASS_NOT_AVAILABLE)

        assertThat(memberPass.remainingCount).isZero()
    }

    @Test
    fun `consume fails when active pass is expired`() {
        val memberPass = memberPass(remainingCount = 1, expiresAt = now)

        assertThatThrownBy {
            memberPass.consume(reservationId = 11, now = now)
        }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_PASS_NOT_AVAILABLE)
    }

    @Test
    fun `consume fails when status is not active`() {
        val statuses = listOf(
            MemberPassStatus.EXHAUSTED,
            MemberPassStatus.EXPIRED,
            MemberPassStatus.CANCELED,
        )

        statuses.forEach { status ->
            assertThatThrownBy {
                memberPass(
                    remainingCount = 1,
                    status = status,
                    expiresAt = now.plusSeconds(60),
                ).consume(reservationId = 11, now = now)
            }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MEMBER_PASS_NOT_AVAILABLE)
        }
    }

    @Test
    fun `exhausted restore transitions to active when pass is not expired`() {
        val memberPass = memberPass(
            remainingCount = 0,
            status = MemberPassStatus.EXHAUSTED,
            expiresAt = now.plusSeconds(60),
        )

        val result = memberPass.restore(
            reservationId = 11,
            reason = PassUsageHistoryReason.RESERVATION_CANCELED,
            now = now,
        )

        assertThat(result.memberPass.remainingCount).isEqualTo(1)
        assertThat(result.memberPass.status).isEqualTo(MemberPassStatus.ACTIVE)
        assertThat(result.usageHistory).isEqualTo(
            PassUsageHistory(
                memberPassId = 1,
                reservationId = 11,
                type = PassUsageHistoryType.RESTORE,
                reason = PassUsageHistoryReason.RESERVATION_CANCELED,
                changedCount = 1,
                remainingCountAfter = 1,
            ),
        )
    }

    @Test
    fun `expired restore increases remaining count but keeps expired status`() {
        val memberPass = memberPass(
            remainingCount = 0,
            status = MemberPassStatus.EXPIRED,
            expiresAt = now.minusSeconds(60),
        )

        val result = memberPass.restore(
            reservationId = 11,
            reason = PassUsageHistoryReason.CLASS_SESSION_CANCELED,
            now = now,
        )

        assertThat(result.memberPass.remainingCount).isEqualTo(1)
        assertThat(result.memberPass.status).isEqualTo(MemberPassStatus.EXPIRED)
        assertThat(result.usageHistory).isEqualTo(
            PassUsageHistory(
                memberPassId = 1,
                reservationId = 11,
                type = PassUsageHistoryType.RESTORE,
                reason = PassUsageHistoryReason.CLASS_SESSION_CANCELED,
                changedCount = 1,
                remainingCountAfter = 1,
            ),
        )
    }

    @Test
    fun `expired exhausted restore increases remaining count but does not transition to active`() {
        val memberPass = memberPass(
            remainingCount = 0,
            status = MemberPassStatus.EXHAUSTED,
            expiresAt = now,
        )

        val result = memberPass.restore(
            reservationId = 11,
            reason = PassUsageHistoryReason.RESERVATION_CANCELED,
            now = now,
        )

        assertThat(result.memberPass.remainingCount).isEqualTo(1)
        assertThat(result.memberPass.status).isEqualTo(MemberPassStatus.EXHAUSTED)
    }

    @Test
    fun `restore fails when remaining count is already total count`() {
        val memberPass = memberPass(remainingCount = 3)

        assertThatThrownBy {
            memberPass.restore(
                reservationId = 11,
                reason = PassUsageHistoryReason.RESERVATION_CANCELED,
                now = now,
            )
        }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_PASS_RESTORE_NOT_ALLOWED)

        assertThat(memberPass.remainingCount).isEqualTo(memberPass.totalCount)
    }

    @Test
    fun `canceled pass cannot be consumed or restored`() {
        val memberPass = memberPass(
            remainingCount = 1,
            status = MemberPassStatus.CANCELED,
            expiresAt = now.plusSeconds(60),
        )

        assertThatThrownBy {
            memberPass.consume(reservationId = 11, now = now)
        }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_PASS_NOT_AVAILABLE)

        assertThatThrownBy {
            memberPass.restore(
                reservationId = 11,
                reason = PassUsageHistoryReason.RESERVATION_CANCELED,
                now = now,
            )
        }
            .isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_PASS_RESTORE_NOT_ALLOWED)
    }

    private fun memberPass(
        remainingCount: Int,
        status: MemberPassStatus = MemberPassStatus.ACTIVE,
        expiresAt: Instant? = null,
    ): MemberPass =
        MemberPass(
            id = 1,
            memberId = 3,
            passProductId = 7,
            productNameSnapshot = "3 Count Pass",
            passTypeSnapshot = PassProductType.COUNT_PASS,
            totalCount = 3,
            remainingCount = remainingCount,
            priceSnapshot = BigDecimal("45000"),
            validDaysSnapshot = 30,
            status = status,
            issuedAt = now.minusSeconds(60),
            expiresAt = expiresAt,
        )
}
