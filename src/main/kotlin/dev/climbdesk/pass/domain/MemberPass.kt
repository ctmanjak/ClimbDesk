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
