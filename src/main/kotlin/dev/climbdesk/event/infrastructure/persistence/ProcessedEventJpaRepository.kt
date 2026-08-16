package dev.climbdesk.event.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface ProcessedEventJpaRepository : JpaRepository<ProcessedEventJpaEntity, ProcessedEventId> {
    @Modifying
    @Query(
        value = """
            insert into processed_events (consumer_name, event_id, event_type, processed_at)
            values (:consumerName, :eventId, :eventType, :processedAt)
            on conflict (consumer_name, event_id) do nothing
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        consumerName: String,
        eventId: Long,
        eventType: String,
        processedAt: Instant,
    ): Int
}
