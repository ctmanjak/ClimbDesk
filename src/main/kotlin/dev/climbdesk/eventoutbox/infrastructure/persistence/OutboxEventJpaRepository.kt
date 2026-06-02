package dev.climbdesk.eventoutbox.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface OutboxEventJpaRepository : JpaRepository<OutboxEventJpaEntity, Long>
