package dev.climbdesk.event.infrastructure.observability

import dev.climbdesk.event.application.FailureRepublishDestination
import dev.climbdesk.event.application.MessagingObservation
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class MessagingMetrics(
    private val jdbcTemplate: JdbcTemplate,
    private val maxPublishAttempts: Int,
    private val clock: Clock = Clock.systemUTC(),
) : MessagingObservation, MeterBinder {
    private lateinit var registry: MeterRegistry
    private val collectionHealthy = AtomicBoolean(true)

    override fun bindTo(registry: MeterRegistry) {
        this.registry = registry
        listOf("pending", "retry", "terminal").forEach { state ->
            Gauge.builder("climbdesk.messaging.outbox.events") { outboxCount(state) }
                .description("Current RabbitMQ-targeted unpublished Outbox rows")
                .baseUnit("events")
                .tag("state", state)
                .register(registry)
        }
        Gauge.builder("climbdesk.messaging.outbox.oldest.unpublished.age") { oldestUnpublishedAgeSeconds() }
            .description("Age of the oldest RabbitMQ-targeted unpublished Outbox row")
            .baseUnit("seconds")
            .register(registry)
    }

    override fun outboxPublishFailed() = safelyRecord {
        afterCommit {
            safelyRecord {
                Counter.builder("climbdesk.messaging.outbox.publish.failures")
                    .description("Outbox publish attempts whose failure state committed")
                    .register(registry)
                    .increment()
            }
        }
    }

    override fun consumerProcessed(occurredAt: Instant) {
        safelyRecord {
            val latency = Duration.between(occurredAt, clock.instant()).coerceAtLeast(Duration.ZERO)
            Timer.builder("climbdesk.messaging.consumer.processing.latency")
                .description("Committed event occurrence-to-consumer-result latency")
                .publishPercentileHistogram()
                .register(registry)
                .record(latency)
        }
    }

    override fun consumerDuplicate() {
        safelyRecord {
            Counter.builder("climbdesk.messaging.consumer.duplicates")
                .description("Duplicate deliveries committed as a business no-op")
                .register(registry)
                .increment()
        }
    }

    override fun failureRepublishConfirmed(destination: FailureRepublishDestination) {
        safelyRecord {
            Counter.builder("climbdesk.messaging.consumer.failure.republish.confirmed")
                .description("Retry or DLQ republishes confirmed by RabbitMQ without a mandatory return")
                .tag("destination", destination.name.lowercase())
                .register(registry)
                .increment()
        }
    }

    override fun failureRepublishFailed(destination: FailureRepublishDestination) {
        safelyRecord {
            Counter.builder("climbdesk.messaging.consumer.failure.republish.failures")
                .description("Retry or DLQ republishes not safely confirmed")
                .tag("destination", destination.name.lowercase())
                .register(registry)
                .increment()
        }
    }

    private fun outboxCount(state: String): Double = safely {
        val condition = when (state) {
            "pending" -> "status = 'PENDING'"
            "retry" -> "status = 'FAILED' and retry_count < ? and next_retry_at is not null"
            else -> "status = 'FAILED' and (retry_count >= ? or next_retry_at is null)"
        }
        val arguments = if (state == "pending") emptyArray() else arrayOf(maxPublishAttempts)
        jdbcTemplate.queryForObject(
            "select count(*) from outbox_events where publish_target = 'RABBITMQ' and $condition",
            Long::class.java,
            *arguments,
        )!!.toDouble()
    }

    private fun oldestUnpublishedAgeSeconds(): Double = safely {
        val occurredAt = jdbcTemplate.queryForObject(
            """
                select min(occurred_at)
                from outbox_events
                where publish_target = 'RABBITMQ' and status <> 'PUBLISHED'
            """.trimIndent(),
            Instant::class.java,
        ) ?: return@safely 0.0
        Duration.between(occurredAt, clock.instant()).seconds.coerceAtLeast(0).toDouble()
    }

    private fun safely(query: () -> Double): Double =
        try {
            query().also { collectionHealthy.set(true) }
        } catch (exception: Exception) {
            if (collectionHealthy.getAndSet(false)) {
                logger.warn("Messaging metric collection failed: exceptionClass={}", exception.javaClass.simpleName)
            }
            Double.NaN
        }

    private fun safelyRecord(action: () -> Unit) {
        try {
            action()
        } catch (exception: Exception) {
            logger.warn("Messaging metric recording failed: exceptionClass={}", exception.javaClass.simpleName)
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(MessagingMetrics::class.java)
    }
}
