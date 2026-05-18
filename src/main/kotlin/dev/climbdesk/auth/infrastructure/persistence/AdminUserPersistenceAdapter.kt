package dev.climbdesk.auth.infrastructure.persistence

import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRepository
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

@Repository
class AdminUserPersistenceAdapter(
    private val adminUserJpaRepository: AdminUserJpaRepository,
) : AdminUserRepository {
    override fun findByEmail(email: String): AdminUser? =
        adminUserJpaRepository.findByEmail(email)?.toDomain()

    override fun existsByEmail(email: String): Boolean =
        adminUserJpaRepository.existsByEmail(email)

    override fun save(adminUser: AdminUser): AdminUser =
        try {
            adminUserJpaRepository.saveAndFlush(adminUser.toJpaEntity()).toDomain()
        } catch (exception: DataIntegrityViolationException) {
            throw ApplicationException(ErrorCode.DUPLICATE_ADMIN_USER_EMAIL, cause = exception)
        } catch (exception: ConstraintViolationException) {
            throw ApplicationException(ErrorCode.DUPLICATE_ADMIN_USER_EMAIL, cause = exception)
        }
}
