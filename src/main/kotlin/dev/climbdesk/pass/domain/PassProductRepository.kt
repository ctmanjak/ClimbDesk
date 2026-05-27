package dev.climbdesk.pass.domain

interface PassProductRepository {
    fun findById(passProductId: Long): PassProduct?
    fun findPage(page: Int, size: Int): PassProductPage
    fun save(passProduct: PassProduct): PassProduct
}

data class PassProductPage(
    val items: List<PassProduct>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
