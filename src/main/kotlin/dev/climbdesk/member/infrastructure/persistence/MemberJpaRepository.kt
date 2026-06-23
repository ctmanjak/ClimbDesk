package dev.climbdesk.member.infrastructure.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface MemberJpaRepository : JpaRepository<MemberJpaEntity, Long> {
    fun existsByPhone(phone: String): Boolean
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): Page<MemberJpaEntity>
}
