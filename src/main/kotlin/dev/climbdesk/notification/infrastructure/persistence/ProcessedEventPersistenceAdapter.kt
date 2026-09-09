package dev.climbdesk.notification.infrastructure.persistence

import dev.climbdesk.event.infrastructure.persistence.ProcessedEventJpaRepository
import dev.climbdesk.notification.application.ProcessedEventStore
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ProcessedEventPersistenceAdapter(
    private val repository: ProcessedEventJpaRepository,
) : ProcessedEventStore {
    override fun insertIfAbsent(
        consumerName: String,
        eventId: Long,
        eventType: String,
        processedAt: Instant,
    ): Boolean = repository.insertIfAbsent(consumerName, eventId, eventType, processedAt) == 1
}
