package dev.climbdesk.pass.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface MemberPassJpaRepository : JpaRepository<MemberPassJpaEntity, Long>
