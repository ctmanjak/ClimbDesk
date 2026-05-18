package dev.climbdesk.auth.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface AdminUserJpaRepository : JpaRepository<AdminUserJpaEntity, Long> {
    fun findByEmail(email: String): AdminUserJpaEntity?
}
