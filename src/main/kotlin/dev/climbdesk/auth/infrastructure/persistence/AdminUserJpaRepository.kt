package dev.climbdesk.auth.infrastructure.persistence

import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface AdminUserJpaRepository : JpaRepository<AdminUserJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select adminUser from AdminUserJpaEntity adminUser where adminUser.id = :id")
    fun findByIdForUpdate(id: Long): AdminUserJpaEntity?

    fun findByEmail(email: String): AdminUserJpaEntity?
    fun existsByEmail(email: String): Boolean
    fun countByStatusAndRole(
        status: AdminUserStatus,
        role: AdminUserRole,
    ): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select adminUser from AdminUserJpaEntity adminUser " +
            "where adminUser.status = :status and adminUser.role = :role",
    )
    fun findByStatusAndRoleForUpdate(
        status: AdminUserStatus,
        role: AdminUserRole,
    ): List<AdminUserJpaEntity>
}
