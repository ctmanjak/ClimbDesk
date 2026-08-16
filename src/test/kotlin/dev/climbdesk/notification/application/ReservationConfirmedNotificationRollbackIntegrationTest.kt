package dev.climbdesk.notification.application

import dev.climbdesk.event.infrastructure.persistence.ProcessedEventJpaRepository
import dev.climbdesk.notification.domain.ReservationNotificationRequest
import dev.climbdesk.notification.infrastructure.persistence.ReservationNotificationRequestJpaRepository
import dev.climbdesk.notification.infrastructure.persistence.ReservationNotificationRequestPersistenceAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.messaging.rabbitmq.enabled=false",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///reservation-notification-rollback",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@Import(ReservationConfirmedNotificationRollbackIntegrationTest.FailFirstStoreConfiguration::class)
class ReservationConfirmedNotificationRollbackIntegrationTest @Autowired constructor(
    private val handler: ReservationConfirmedNotificationUseCase,
    private val processedEventRepository: ProcessedEventJpaRepository,
    private val notificationRequestRepository: ReservationNotificationRequestJpaRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun setUp() {
        clearData()
        saveReservation()
    }

    @AfterEach
    fun tearDown() {
        clearData()
    }

    @Test
    fun `notification persistence failure rolls back processed insert and the same event can succeed on redelivery`() {
        assertThatThrownBy { handler.handle(command()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("test-only notification save failure")

        assertThat(processedEventRepository.count()).isZero()
        assertThat(notificationRequestRepository.count()).isZero()

        assertThat(handler.handle(command())).isEqualTo(ReservationNotificationHandlingResult.PROCESSED)
        assertThat(processedEventRepository.count()).isEqualTo(1)
        assertThat(notificationRequestRepository.count()).isEqualTo(1)
    }

    private fun command() =
        ReservationConfirmedNotificationCommand(
            eventId = 101,
            reservationId = 201,
            memberId = 301,
            classSessionId = 401,
            memberPassId = 501,
        )

    private fun saveReservation() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        jdbcTemplate.update(
            "insert into members (id, name, phone, status, created_at, updated_at) values (301, 'Member', '01099999999', 'ACTIVE', ?, ?)",
            now.toSqlTimestamp(),
            now.toSqlTimestamp(),
        )
        jdbcTemplate.update(
            "insert into pass_products (id, name, type, total_count, valid_days, created_at, updated_at) values (601, 'Pass', 'COUNT_PASS', 10, 90, ?, ?)",
            now.toSqlTimestamp(),
            now.toSqlTimestamp(),
        )
        jdbcTemplate.update(
            """
            insert into class_sessions
                (id, title, starts_at, ends_at, capacity, reserved_count, status, created_at, updated_at)
            values (401, 'Class', ?, ?, 10, 1, 'OPEN', ?, ?)
            """.trimIndent(),
            now.plusSeconds(3600).toSqlTimestamp(),
            now.plusSeconds(7200).toSqlTimestamp(),
            now.toSqlTimestamp(),
            now.toSqlTimestamp(),
        )
        jdbcTemplate.update(
            """
            insert into member_passes
                (id, member_id, pass_product_id, product_name_snapshot, pass_type_snapshot,
                 total_count, remaining_count, valid_days_snapshot, status, issued_at, expires_at,
                 version, created_at, updated_at)
            values (501, 301, 601, 'Pass', 'COUNT_PASS', 10, 9, 90, 'ACTIVE', ?, ?, 0, ?, ?)
            """.trimIndent(),
            now.minusSeconds(3600).toSqlTimestamp(),
            now.plusSeconds(7_776_000).toSqlTimestamp(),
            now.toSqlTimestamp(),
            now.toSqlTimestamp(),
        )
        jdbcTemplate.update(
            """
            insert into reservations
                (id, member_id, class_session_id, member_pass_id, status, reserved_at, created_at, updated_at)
            values (201, 301, 401, 501, 'CONFIRMED', ?, ?, ?)
            """.trimIndent(),
            now.toSqlTimestamp(),
            now.toSqlTimestamp(),
            now.toSqlTimestamp(),
        )
    }

    private fun clearData() {
        notificationRequestRepository.deleteAll()
        processedEventRepository.deleteAll()
        jdbcTemplate.update("delete from reservations")
        jdbcTemplate.update("delete from member_passes")
        jdbcTemplate.update("delete from pass_products")
        jdbcTemplate.update("delete from class_sessions")
        jdbcTemplate.update("delete from members")
    }

    @TestConfiguration
    class FailFirstStoreConfiguration {
        @Bean
        @Primary
        fun failFirstNotificationStore(
            delegate: ReservationNotificationRequestPersistenceAdapter,
        ): ReservationNotificationRequestStore {
            val failFirst = AtomicBoolean(true)
            return object : ReservationNotificationRequestStore {
                override fun save(request: ReservationNotificationRequest) {
                    if (failFirst.compareAndSet(true, false)) {
                        throw IllegalStateException("test-only notification save failure")
                    }
                    delegate.save(request)
                }
            }
        }
    }
}

private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)
