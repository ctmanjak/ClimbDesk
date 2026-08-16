package dev.climbdesk.notification.application

import java.time.Instant

interface ProcessedEventStore {
    fun insertIfAbsent(
        consumerName: String,
        eventId: Long,
        eventType: String,
        processedAt: Instant,
    ): Boolean
}
