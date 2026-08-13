package dev.climbdesk.event.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface ProcessedEventJpaRepository : JpaRepository<ProcessedEventJpaEntity, ProcessedEventId>
