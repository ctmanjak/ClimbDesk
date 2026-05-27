package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.pass.domain.PassProduct

fun PassProductJpaEntity.toDomain(): PassProduct =
    PassProduct(
        id = id,
        name = name,
        type = type,
        totalCount = totalCount,
        price = price,
        validDays = validDays,
        createdAt = createdAt,
    )

fun PassProduct.toJpaEntity(): PassProductJpaEntity =
    PassProductJpaEntity(
        id = id,
        name = name,
        type = type,
        totalCount = totalCount,
        price = price,
        validDays = validDays,
        createdAt = createdAt,
    )
