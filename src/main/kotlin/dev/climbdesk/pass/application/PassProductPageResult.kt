package dev.climbdesk.pass.application

import dev.climbdesk.pass.domain.PassProductPage

data class PassProductPageResult(
    val items: List<PassProductResult>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(passProductPage: PassProductPage): PassProductPageResult =
            PassProductPageResult(
                items = passProductPage.items.map(PassProductResult::from),
                page = passProductPage.page,
                size = passProductPage.size,
                totalElements = passProductPage.totalElements,
                totalPages = if (passProductPage.totalElements == 0L) {
                    0
                } else {
                    ((passProductPage.totalElements - 1) / passProductPage.size + 1).toInt()
                },
            )
    }
}
