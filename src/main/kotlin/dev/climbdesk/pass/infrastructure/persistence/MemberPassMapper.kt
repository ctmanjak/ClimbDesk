package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.pass.domain.MemberPass

fun MemberPassJpaEntity.toDomain(): MemberPass =
    MemberPass(
        id = id,
        memberId = memberId,
        passProductId = passProductId,
        productNameSnapshot = productNameSnapshot,
        passTypeSnapshot = passTypeSnapshot,
        totalCount = totalCount,
        remainingCount = remainingCount,
        priceSnapshot = priceSnapshot,
        validDaysSnapshot = validDaysSnapshot,
        status = status,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun MemberPass.toJpaEntity(): MemberPassJpaEntity =
    MemberPassJpaEntity(
        id = id,
        memberId = memberId,
        passProductId = passProductId,
        productNameSnapshot = productNameSnapshot,
        passTypeSnapshot = passTypeSnapshot,
        totalCount = totalCount,
        remainingCount = remainingCount,
        priceSnapshot = priceSnapshot,
        validDaysSnapshot = validDaysSnapshot,
        status = status,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
