package dev.climbdesk.pass.application

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.pass.domain.PassProduct
import dev.climbdesk.pass.domain.PassProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PassProductApplicationService(
    private val passProductRepository: PassProductRepository,
) {
    @Transactional
    fun createPassProduct(command: CreatePassProductCommand): PassProductResult {
        val passProduct = PassProduct.createCountPass(
            name = command.name,
            totalCount = command.totalCount,
            price = command.price,
            validDays = command.validDays,
        )

        return PassProductResult.from(passProductRepository.save(passProduct))
    }

    @Transactional(readOnly = true)
    fun listPassProducts(page: Int, size: Int): PassProductPageResult {
        if (page < 0) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "page must be greater than or equal to 0.")
        }
        if (size !in 1..MAX_PASS_PRODUCT_PAGE_SIZE) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "size must be between 1 and $MAX_PASS_PRODUCT_PAGE_SIZE.")
        }

        return PassProductPageResult.from(passProductRepository.findPage(page, size))
    }

    @Transactional(readOnly = true)
    fun getPassProduct(passProductId: Long): PassProductResult =
        passProductRepository.findById(passProductId)
            ?.let(PassProductResult::from)
            ?: throw ApplicationException(ErrorCode.PASS_PRODUCT_NOT_FOUND)
}
