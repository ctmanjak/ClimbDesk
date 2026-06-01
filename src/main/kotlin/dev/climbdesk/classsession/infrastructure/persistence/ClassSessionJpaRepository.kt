package dev.climbdesk.classsession.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface ClassSessionJpaRepository : JpaRepository<ClassSessionJpaEntity, Long>
