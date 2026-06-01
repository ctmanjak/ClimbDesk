package dev.climbdesk.classsession.infrastructure.persistence

import dev.climbdesk.classsession.domain.ClassSessionStatus
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
@Table(name = "class_sessions")
class ClassSessionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 150)
    val title: String,

    @Column(name = "starts_at", nullable = false)
    val startsAt: Instant,

    @Column(name = "ends_at", nullable = false)
    val endsAt: Instant,

    @Column(nullable = false)
    val capacity: Int,

    @Column(name = "reserved_count", nullable = false)
    val reservedCount: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: ClassSessionStatus,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,

    @Column(name = "canceled_at")
    val canceledAt: Instant? = null,

    @Column(name = "cancel_reason", length = 500)
    val cancelReason: String? = null,

    @Column(name = "affected_reservation_count", nullable = false)
    val affectedReservationCount: Int = 0,
) {
    @PrePersist
    fun prePersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
