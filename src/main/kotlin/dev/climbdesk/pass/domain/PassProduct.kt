package dev.climbdesk.pass.domain

import dev.climbdesk.common.error.DomainException
import dev.climbdesk.common.error.ErrorCode
import java.math.BigDecimal
import java.time.Instant

data class PassProduct(
    val id: Long,
    val name: String,
    val type: PassProductType,
    val totalCount: Int,
    val price: BigDecimal?,
    val validDays: Int?,
    val createdAt: Instant? = null,
) {
    companion object {
        fun createCountPass(
            name: String,
            totalCount: Int,
            price: BigDecimal?,
            validDays: Int?,
        ): PassProduct {
            if (totalCount < 1) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "totalCount must be greater than or equal to 1.")
            }
            if (price != null && price < BigDecimal.ZERO) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "price must be greater than or equal to 0.")
            }
            if (validDays != null && validDays < 1) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "validDays must be greater than or equal to 1.")
            }

            return PassProduct(
                id = 0,
                name = name,
                type = PassProductType.COUNT_PASS,
                totalCount = totalCount,
                price = price,
                validDays = validDays,
            )
        }
    }
}
