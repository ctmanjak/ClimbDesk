package dev.climbdesk.auth.infrastructure.adapter

import dev.climbdesk.auth.domain.AdminUserRole

data class JwtAuthenticationPrincipal(
    val adminUserId: Long,
    val email: String,
    val role: AdminUserRole,
)
