package dev.climbdesk.event.infrastructure.observability

import dev.climbdesk.event.application.FailureRepublishDestination
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.sql.Timestamp

@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///messaging-metrics",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@AutoConfigureMockMvc
class MessagingMetricsIntegrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val mockMvc: MockMvc,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("delete from outbox_events")
    }

    @Test
    fun `prometheus endpoint is not anonymously accessible`() {
        mockMvc.get("/actuator/prometheus").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `outbox state gauges exclude NONE and counters describe committed outcomes only`() {
        val now = Instant.parse("2026-09-09T08:00:00Z")
        insert("NONE", "PENDING", 0, null, now.minusSeconds(10_000))
        insert("RABBITMQ", "PENDING", 0, null, now.minusSeconds(120))
        insert("RABBITMQ", "FAILED", 2, now.plusSeconds(30), now.minusSeconds(60))
        insert("RABBITMQ", "FAILED", 5, null, now.minusSeconds(30))
        insert("RABBITMQ", "PUBLISHED", 0, null, now.minusSeconds(20_000))

        val registry = SimpleMeterRegistry()
        val metrics = MessagingMetrics(jdbcTemplate, 5, Clock.fixed(now, ZoneOffset.UTC))
        metrics.bindTo(registry)

        assertThat(outboxGauge(registry, "pending")).isEqualTo(1.0)
        assertThat(outboxGauge(registry, "retry")).isEqualTo(1.0)
        assertThat(outboxGauge(registry, "terminal")).isEqualTo(1.0)
        assertThat(registry.get("climbdesk.messaging.outbox.oldest.unpublished.age").gauge().value()).isEqualTo(120.0)

        transactions.executeWithoutResult { status ->
            metrics.outboxPublishFailed()
            status.setRollbackOnly()
        }
        assertThat(registry.find("climbdesk.messaging.outbox.publish.failures").counter()).isNull()
        transactions.executeWithoutResult { metrics.outboxPublishFailed() }
        assertThat(registry.get("climbdesk.messaging.outbox.publish.failures").counter().count()).isEqualTo(1.0)

        metrics.consumerProcessed(now.minusSeconds(2))
        metrics.consumerDuplicate()
        metrics.failureRepublishConfirmed(FailureRepublishDestination.RETRY)
        metrics.failureRepublishConfirmed(FailureRepublishDestination.DLQ)
        metrics.failureRepublishFailed(FailureRepublishDestination.RETRY)

        assertThat(registry.get("climbdesk.messaging.consumer.processing.latency").timer().count()).isEqualTo(1)
        assertThat(registry.get("climbdesk.messaging.consumer.duplicates").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("climbdesk.messaging.consumer.failure.republish.confirmed").tag("destination", "retry").counter().count())
            .isEqualTo(1.0)
        assertThat(registry.get("climbdesk.messaging.consumer.failure.republish.confirmed").tag("destination", "dlq").counter().count())
            .isEqualTo(1.0)
        assertThat(registry.get("climbdesk.messaging.consumer.failure.republish.failures").tag("destination", "retry").counter().count())
            .isEqualTo(1.0)
        assertThat(registry.meters.flatMap { it.id.tags }.map { it.key })
            .doesNotContain("eventId", "messageId", "userId", "exception")
    }

    @Test
    fun `terminal Outbox runbook requeues only one still-terminal RabbitMQ row`() {
        val now = Instant.parse("2026-09-09T08:00:00Z")
        insert("RABBITMQ", "FAILED", 5, null, now)
        val eventId = checkNotNull(jdbcTemplate.queryForObject("select max(id) from outbox_events", Long::class.java))

        val updated = requeueTerminal(eventId)
        val secondAttempt = requeueTerminal(eventId)
        val row = jdbcTemplate.queryForMap(
            "select status, retry_count, next_retry_at, last_error, published_at from outbox_events where id = ?",
            eventId,
        )

        assertThat(updated).isEqualTo(1)
        assertThat(secondAttempt).isZero()
        assertThat(row["status"]).isEqualTo("PENDING")
        assertThat(row["retry_count"]).isEqualTo(0)
        assertThat(row["next_retry_at"]).isNull()
        assertThat(row["last_error"]).isNull()
        assertThat(row["published_at"]).isNull()
    }

    private fun requeueTerminal(eventId: Long): Int = transactions.execute {
        jdbcTemplate.update(
            """
                update outbox_events
                set status = 'PENDING', retry_count = 0, next_retry_at = null,
                    last_error = null, published_at = null, updated_at = now()
                where id = ? and publish_target = 'RABBITMQ' and status = 'FAILED'
                  and retry_count >= 5 and next_retry_at is null
            """.trimIndent(),
            eventId,
        )
    } ?: 0

    private fun outboxGauge(registry: SimpleMeterRegistry, state: String): Double =
        registry.get("climbdesk.messaging.outbox.events").tag("state", state).gauge().value()

    private fun insert(
        target: String,
        status: String,
        retryCount: Int,
        nextRetryAt: Instant?,
        occurredAt: Instant,
    ) {
        jdbcTemplate.update(
            """
                insert into outbox_events
                    (event_type, aggregate_type, aggregate_id, payload, status, publish_target,
                     schema_version, retry_count, occurred_at, published_at, next_retry_at, created_at, updated_at)
                values ('ReservationConfirmedEvent', 'Reservation', 1, '{}'::jsonb, ?, ?, 1, ?, ?,
                        case when ? = 'PUBLISHED' then now() else null end, ?, now(), now())
            """.trimIndent(),
            status,
            target,
            retryCount,
            Timestamp.from(occurredAt),
            status,
            nextRetryAt?.let(Timestamp::from),
        )
    }
}
