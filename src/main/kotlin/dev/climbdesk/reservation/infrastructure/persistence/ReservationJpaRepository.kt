package dev.climbdesk.reservation.infrastructure.persistence

import dev.climbdesk.reservation.domain.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationJpaRepository : JpaRepository<ReservationJpaEntity, Long> {
    fun existsByMemberIdAndClassSessionIdAndStatus(
        memberId: Long,
        classSessionId: Long,
        status: ReservationStatus,
    ): Boolean
}
