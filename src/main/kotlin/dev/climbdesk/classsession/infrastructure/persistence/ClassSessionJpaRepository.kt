package dev.climbdesk.classsession.infrastructure.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ClassSessionJpaRepository : JpaRepository<ClassSessionJpaEntity, Long> {
    fun findAllByOrderByStartsAtDescIdDesc(pageable: Pageable): Page<ClassSessionJpaEntity>
}
