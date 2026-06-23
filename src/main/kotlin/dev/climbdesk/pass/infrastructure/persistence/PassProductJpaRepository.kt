package dev.climbdesk.pass.infrastructure.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PassProductJpaRepository : JpaRepository<PassProductJpaEntity, Long> {
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): Page<PassProductJpaEntity>
}
