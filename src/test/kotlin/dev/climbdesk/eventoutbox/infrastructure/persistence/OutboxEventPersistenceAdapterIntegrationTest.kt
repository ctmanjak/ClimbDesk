package dev.climbdesk.eventoutbox.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.eventoutbox.application.OutboxEventRecorder
import dev.climbdesk.eventoutbox.domain.OutboxEventStatus
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.auth.jwt.expires-in=3600",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
class OutboxEventPersistenceAdapterIntegrationTest @Autowired constructor(
    private val outboxEventRecorder: OutboxEventRecorder,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) {
    @BeforeEach
    fun setUp() {
        outboxEventJpaRepository.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        outboxEventJpaRepository.deleteAll()
    }

    @Test
    fun `record persists pending reservation confirmed event with jsonb payload`() {
        val occurredAt = Instant.parse("2026-06-02T00:00:00Z")

        val saved = outboxEventRecorder.record(reservationConfirmedEvent(occurredAt))

        val persisted = outboxEventJpaRepository.findById(saved.id).orElseThrow()
        val payload = objectMapper.readTree(persisted.payload)
        assertThat(persisted.eventType).isEqualTo("ReservationConfirmedEvent")
        assertThat(persisted.aggregateType).isEqualTo("Reservation")
        assertThat(persisted.aggregateId).isEqualTo(101L)
        assertThat(persisted.status).isEqualTo(OutboxEventStatus.PENDING)
        assertThat(persisted.retryCount).isZero()
        assertThat(persisted.occurredAt).isEqualTo(occurredAt)
        assertThat(persisted.createdAt).isNotNull()
        assertThat(persisted.updatedAt).isNotNull()
        assertThat(payload["reservationId"].longValue()).isEqualTo(101L)
        assertThat(payload["memberId"].longValue()).isEqualTo(201L)
        assertThat(payload["classSessionId"].longValue()).isEqualTo(301L)
        assertThat(payload["memberPassId"].longValue()).isEqualTo(401L)
        assertThat(payload["occurredAt"].textValue()).isEqualTo("2026-06-02T00:00:00Z")
        assertThat(
            jdbcTemplate.queryForObject(
                "select pg_typeof(payload)::text from outbox_events where id = ?",
                String::class.java,
                saved.id,
            ),
        ).isEqualTo("jsonb")
    }

    @Test
    fun `record joins caller transaction and rolls back with it`() {
        transactionTemplate.executeWithoutResult { transactionStatus ->
            outboxEventRecorder.record(reservationConfirmedEvent())
            transactionStatus.setRollbackOnly()
        }

        assertThat(outboxEventJpaRepository.count()).isZero()
    }

    private fun reservationConfirmedEvent(
        occurredAt: Instant = Instant.parse("2026-06-02T00:00:00Z"),
    ): ReservationConfirmedEvent =
        ReservationConfirmedEvent(
            reservationId = 101L,
            memberId = 201L,
            classSessionId = 301L,
            memberPassId = 401L,
            occurredAt = occurredAt,
        )
}
