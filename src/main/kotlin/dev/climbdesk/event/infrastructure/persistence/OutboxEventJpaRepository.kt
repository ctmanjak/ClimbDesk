package dev.climbdesk.event.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface OutboxEventJpaRepository : JpaRepository<OutboxEventJpaEntity, Long>
