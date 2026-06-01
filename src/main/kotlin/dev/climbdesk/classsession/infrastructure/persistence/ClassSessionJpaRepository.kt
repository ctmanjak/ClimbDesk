package dev.climbdesk.classsession.infrastructure.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ClassSessionJpaRepository : JpaRepository<ClassSessionJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select classSession from ClassSessionJpaEntity classSession where classSession.id = :id")
    fun findByIdForUpdate(id: Long): ClassSessionJpaEntity?

    fun findAllByOrderByStartsAtDescIdDesc(pageable: Pageable): Page<ClassSessionJpaEntity>
}
