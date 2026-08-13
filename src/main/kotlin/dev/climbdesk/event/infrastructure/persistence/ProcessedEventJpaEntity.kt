package dev.climbdesk.event.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEventId::class)
class ProcessedEventJpaEntity(
    @Id
    @Column(name = "consumer_name", nullable = false, length = 100)
    val consumerName: String,

    @Id
    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "processed_at", nullable = false)
    val processedAt: Instant,
)

data class ProcessedEventId(
    var consumerName: String = "",
    var eventId: Long = 0,
) : Serializable
