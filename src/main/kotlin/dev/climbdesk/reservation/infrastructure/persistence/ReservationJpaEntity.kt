package dev.climbdesk.reservation.infrastructure.persistence

import dev.climbdesk.reservation.domain.ReservationCancelReason
import dev.climbdesk.reservation.domain.ReservationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "reservations")
class ReservationJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "class_session_id", nullable = false)
    val classSessionId: Long,

    @Column(name = "member_pass_id", nullable = false)
    val memberPassId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: ReservationStatus,

    @Column(name = "reserved_at", nullable = false)
    val reservedAt: Instant,

    @Column(name = "canceled_at")
    val canceledAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 40)
    val cancelReason: ReservationCancelReason? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,
) {
    @PrePersist
    fun prePersist() {
        val now = Instant.now()
        createdAt = createdAt ?: now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
