package dev.climbdesk.notification.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface ReservationNotificationRequestJpaRepository :
    JpaRepository<ReservationNotificationRequestJpaEntity, Long>
