package dev.climbdesk.member.infrastructure.persistence

import dev.climbdesk.member.domain.Member

fun MemberJpaEntity.toDomain(): Member =
    Member(
        id = id,
        name = name,
        phone = phone,
        email = email,
        status = status,
        createdAt = createdAt,
        deactivatedAt = deactivatedAt,
    )

fun Member.toJpaEntity(): MemberJpaEntity =
    MemberJpaEntity(
        id = id,
        name = name,
        phone = phone,
        email = email,
        status = status,
        createdAt = createdAt,
        deactivatedAt = deactivatedAt,
    )
