package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.pass.domain.PassProduct
import dev.climbdesk.pass.domain.PassProductPage
import dev.climbdesk.pass.domain.PassProductRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class PassProductPersistenceAdapter(
    private val passProductJpaRepository: PassProductJpaRepository,
) : PassProductRepository {
    override fun findById(passProductId: Long): PassProduct? =
        passProductJpaRepository.findById(passProductId)
            .map(PassProductJpaEntity::toDomain)
            .orElse(null)

    override fun findPage(page: Int, size: Int): PassProductPage {
        val passProductPage = passProductJpaRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size))
        return PassProductPage(
            items = passProductPage.content.map(PassProductJpaEntity::toDomain),
            page = passProductPage.number,
            size = passProductPage.size,
            totalElements = passProductPage.totalElements,
        )
    }

    override fun save(passProduct: PassProduct): PassProduct =
        passProductJpaRepository.save(passProduct.toJpaEntity()).toDomain()
}
