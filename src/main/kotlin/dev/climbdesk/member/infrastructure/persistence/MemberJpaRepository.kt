package dev.climbdesk.member.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface MemberJpaRepository : JpaRepository<MemberJpaEntity, Long> {
    fun existsByPhone(phone: String): Boolean
}
