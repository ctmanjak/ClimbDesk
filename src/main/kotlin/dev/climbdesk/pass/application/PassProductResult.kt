package dev.climbdesk.pass.application

import dev.climbdesk.pass.domain.PassProduct
import dev.climbdesk.pass.domain.PassProductType
import java.math.BigDecimal
import java.time.Instant

data class PassProductResult(
    val id: Long,
    val name: String,
    val type: PassProductType,
    val totalCount: Int,
    val price: BigDecimal?,
    val validDays: Int?,
    val createdAt: Instant,
) {
    companion object {
        fun from(passProduct: PassProduct): PassProductResult =
            PassProductResult(
                id = passProduct.id,
                name = passProduct.name,
                type = passProduct.type,
                totalCount = passProduct.totalCount,
                price = passProduct.price,
                validDays = passProduct.validDays,
                createdAt = requireNotNull(passProduct.createdAt) { "PassProduct must have createdAt." },
            )
    }
}
