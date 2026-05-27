package dev.climbdesk.pass.presentation

import dev.climbdesk.pass.application.PassProductPageResult
import dev.climbdesk.pass.application.PassProductResult
import dev.climbdesk.pass.domain.PassProductType
import java.math.BigDecimal
import java.time.Instant

data class PassProductResponse(
    val id: Long,
    val name: String,
    val type: PassProductType,
    val totalCount: Int,
    val price: BigDecimal?,
    val validDays: Int?,
    val createdAt: Instant,
)

data class PassProductListResponse(
    val items: List<PassProductResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun PassProductResult.toResponse(): PassProductResponse =
    PassProductResponse(
        id = id,
        name = name,
        type = type,
        totalCount = totalCount,
        price = price,
        validDays = validDays,
        createdAt = createdAt,
    )

fun PassProductPageResult.toResponse(): PassProductListResponse =
    PassProductListResponse(
        items = items.map { it.toResponse() },
        page = page,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
    )
