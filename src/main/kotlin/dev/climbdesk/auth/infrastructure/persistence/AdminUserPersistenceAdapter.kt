package dev.climbdesk.auth.infrastructure.persistence

import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRepository
import org.springframework.stereotype.Repository

@Repository
class AdminUserPersistenceAdapter(
    private val adminUserJpaRepository: AdminUserJpaRepository,
) : AdminUserRepository {
    override fun findByEmail(email: String): AdminUser? =
        adminUserJpaRepository.findByEmail(email)?.toDomain()
}
