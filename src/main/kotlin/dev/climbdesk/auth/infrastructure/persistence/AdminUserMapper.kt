package dev.climbdesk.auth.infrastructure.persistence

import dev.climbdesk.auth.domain.AdminUser

fun AdminUserJpaEntity.toDomain(): AdminUser =
    AdminUser(
        id = id,
        email = email,
        passwordHash = passwordHash,
        role = role,
        status = status,
        createdAt = createdAt,
    )

fun AdminUser.toJpaEntity(): AdminUserJpaEntity =
    AdminUserJpaEntity(
        id = id,
        email = email,
        passwordHash = passwordHash,
        role = role,
        status = status,
        createdAt = createdAt,
    )
