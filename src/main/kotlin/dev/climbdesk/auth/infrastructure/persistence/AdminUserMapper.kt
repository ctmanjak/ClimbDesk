package dev.climbdesk.auth.infrastructure.persistence

import dev.climbdesk.auth.domain.AdminUser

fun AdminUserJpaEntity.toDomain(): AdminUser =
    AdminUser(
        id = id,
        email = email,
        passwordHash = passwordHash,
        role = role,
        status = status,
    )
