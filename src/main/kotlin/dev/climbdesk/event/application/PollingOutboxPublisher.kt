package dev.climbdesk.event.application

import dev.climbdesk.event.domain.OutboxEvent
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import kotlin.math.max

fun interface OutboxPublishUseCase {
    fun publishNext(now: Instant): Boolean
}

open class PollingOutboxPublisher(
    private val outboxEventStore: OutboxEventStore,
    private val outboxMessageMapper: OutboxMessageMapper,
    private val outboundMessagePublisher: OutboundMessagePublisher,
    private val policy: OutboxPublisherPolicy,
    private val clock: Clock = Clock.systemUTC(),
) : OutboxPublishUseCase {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open override fun publishNext(now: Instant): Boolean {
        val outboxEvent = outboxEventStore.claimNext(now, policy.maxAttempts) ?: return false
        val message = try {
            outboxMessageMapper.map(outboxEvent)
        } catch (exception: UnsupportedOutboxContractException) {
            outboxEventStore.save(
                outboxEvent.markFailed(
                    retryCount = max(outboxEvent.retryCount, policy.maxAttempts),
                    nextRetryAt = null,
                    lastError = sanitize(exception.message ?: "Unsupported Outbox contract"),
                ),
            )
            return true
        } catch (exception: Exception) {
            recordFailure(
                outboxEvent = outboxEvent,
                failedAt = clock.instant(),
                reason = "Outbox message mapping failed: ${exception.javaClass.simpleName}",
            )
            return true
        }

        val publishResult = try {
            outboundMessagePublisher.publish(message, policy.confirmTimeout)
        } catch (exception: Exception) {
            PublishResult.Failure("Outbound publish failed: ${exception.javaClass.simpleName}")
        }

        val completedAt = clock.instant()
        when (publishResult) {
            PublishResult.Success -> outboxEventStore.save(outboxEvent.markPublished(completedAt))
            is PublishResult.Failure -> recordFailure(outboxEvent, completedAt, publishResult.reason)
        }
        return true
    }

    private fun recordFailure(
        outboxEvent: OutboxEvent,
        failedAt: Instant,
        reason: String,
    ) {
        val retryCount = outboxEvent.retryCount + 1
        val nextRetryAt =
            if (retryCount >= policy.maxAttempts) {
                null
            } else {
                failedAt.plus(policy.retryBackoffs[retryCount - 1])
            }
        outboxEventStore.save(
            outboxEvent.markFailed(
                retryCount = retryCount,
                nextRetryAt = nextRetryAt,
                lastError = sanitize(reason),
            ),
        )
    }

    private fun sanitize(reason: String): String =
        reason.replace(WHITESPACE, " ").trim().ifEmpty { "Unknown publish failure" }.take(MAX_LAST_ERROR_LENGTH)

    private companion object {
        const val MAX_LAST_ERROR_LENGTH = 1_000
        val WHITESPACE = Regex("\\s+")
    }
}
