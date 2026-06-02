package dev.climbdesk.eventoutbox.infrastructure.persistence

import dev.climbdesk.eventoutbox.domain.OutboxEventStatus
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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "outbox_events")
class OutboxEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "aggregate_type", nullable = false, length = 100)
    val aggregateType: String,

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: Long,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: OutboxEventStatus,

    @Column(name = "retry_count", nullable = false)
    val retryCount: Int,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,

    @Column(name = "published_at")
    val publishedAt: Instant? = null,

    @Column(name = "next_retry_at")
    val nextRetryAt: Instant? = null,

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
