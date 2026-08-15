package dev.climbdesk.event.infrastructure.scheduling

import dev.climbdesk.event.application.OutboxPublisherPolicy
import dev.climbdesk.event.application.OutboxPublishUseCase
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant

class OutboxPublishScheduler(
    private val pollingOutboxPublisher: OutboxPublishUseCase,
    private val policy: OutboxPublisherPolicy,
) {
    @Scheduled(
        fixedDelayString = "\${climbdesk.messaging.rabbitmq.publisher.poll-interval}",
        initialDelayString = "\${climbdesk.messaging.rabbitmq.publisher.poll-interval}",
    )
    fun publishDueEvents() {
        repeat(policy.maxPerTick) {
            if (!pollingOutboxPublisher.publishNext(Instant.now())) {
                return
            }
        }
    }
}
