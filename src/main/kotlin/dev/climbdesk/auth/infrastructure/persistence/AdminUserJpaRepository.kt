package dev.climbdesk.auth.infrastructure.persistence

import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import org.springframework.data.jpa.repository.JpaRepository

interface AdminUserJpaRepository : JpaRepository<AdminUserJpaEntity, Long> {
    fun findByEmail(email: String): AdminUserJpaEntity?
    fun existsByEmail(email: String): Boolean
    fun countByStatusAndRole(
        status: AdminUserStatus,
        role: AdminUserRole,
    ): Long
}
