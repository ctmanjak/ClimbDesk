package dev.climbdesk.pass.domain

import dev.climbdesk.common.error.DomainException
import dev.climbdesk.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PassProductTest {
    @Test
    fun `count pass product can be created when total count is positive`() {
        val passProduct = PassProduct.createCountPass(
            name = "10 Count Pass",
            totalCount = 10,
            price = BigDecimal("150000"),
            validDays = 90,
        )

        assertThat(passProduct.type).isEqualTo(PassProductType.COUNT_PASS)
        assertThat(passProduct.totalCount).isEqualTo(10)
        assertThat(passProduct.price).isEqualByComparingTo(BigDecimal("150000"))
        assertThat(passProduct.validDays).isEqualTo(90)
    }

    @Test
    fun `count pass product rejects zero total count`() {
        assertThatThrownBy {
            PassProduct.createCountPass(
                name = "Invalid Pass",
                totalCount = 0,
                price = null,
                validDays = null,
            )
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `count pass product rejects negative total count`() {
        assertThatThrownBy {
            PassProduct.createCountPass(
                name = "Invalid Pass",
                totalCount = -1,
                price = null,
                validDays = null,
            )
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `count pass product rejects negative price`() {
        assertThatThrownBy {
            PassProduct.createCountPass(
                name = "Invalid Pass",
                totalCount = 10,
                price = BigDecimal("-1"),
                validDays = null,
            )
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `count pass product rejects non-positive valid days`() {
        assertThatThrownBy {
            PassProduct.createCountPass(
                name = "Invalid Pass",
                totalCount = 10,
                price = null,
                validDays = 0,
            )
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }
}
