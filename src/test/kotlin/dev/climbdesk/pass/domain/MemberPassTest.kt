package dev.climbdesk.pass.domain

import dev.climbdesk.common.error.DomainException
import dev.climbdesk.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class MemberPassTest {
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
}
