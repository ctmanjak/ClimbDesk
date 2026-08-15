package dev.climbdesk.event.infrastructure.scheduling

import dev.climbdesk.event.application.OutboxPublisherPolicy
import dev.climbdesk.event.application.OutboxPublishUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.slf4j.LoggerFactory
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
        var processedCount = 0
        repeat(policy.maxPerTick) {
            val processed = try {
                pollingOutboxPublisher.publishNext(Instant.now())
            } catch (exception: Exception) {
                logger.error(
                    "Outbox Publisher tick stopped after {} successfully processed events",
                    processedCount,
                    exception,
                )
                return
            }
            if (!processed) {
                logProcessedCount(processedCount)
                return
            }
            processedCount += 1
        }
        logProcessedCount(processedCount)
    }

    private fun logProcessedCount(processedCount: Int) {
        if (processedCount > 0) {
            logger.info("Outbox Publisher tick processed {} events", processedCount)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(OutboxPublishScheduler::class.java)
    }
}
