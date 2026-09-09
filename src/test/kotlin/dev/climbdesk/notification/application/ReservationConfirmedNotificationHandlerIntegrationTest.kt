package dev.climbdesk.notification.application

import dev.climbdesk.TestConcurrencyUtils
import dev.climbdesk.event.infrastructure.persistence.ProcessedEventJpaRepository
import dev.climbdesk.notification.domain.ReservationNotificationStatus
import dev.climbdesk.notification.domain.ReservationNotificationType
import dev.climbdesk.notification.infrastructure.persistence.ReservationNotificationRequestJpaEntity
import dev.climbdesk.notification.infrastructure.persistence.ReservationNotificationRequestJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.messaging.rabbitmq.enabled=false",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///reservation-notification-handler",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@Import(ReservationConfirmedNotificationHandlerIntegrationTest.FixedClockConfiguration::class)
class ReservationConfirmedNotificationHandlerIntegrationTest @Autowired constructor(
    private val handler: ReservationConfirmedNotificationUseCase,
    private val processedEventRepository: ProcessedEventJpaRepository,
    private val notificationRequestRepository: ReservationNotificationRequestJpaRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun setUp() {
        clearData()
    }

    @AfterEach
    fun tearDown() {
        clearData()
    }

    @Test
    fun `confirmed reservation creates one processed event and one ready notification at the same processing time`() {
        saveReservation(canceled = false)

        assertThat(handler.handle(command())).isEqualTo(ReservationNotificationHandlingResult.PROCESSED)

        val processed = processedEventRepository.findAll().single()
        assertThat(processed.consumerName).isEqualTo(ReservationConfirmedNotificationHandler.CONSUMER_NAME)
        assertThat(processed.eventId).isEqualTo(EVENT_ID)
        assertThat(processed.eventType).isEqualTo(ReservationConfirmedNotificationHandler.EVENT_TYPE)
        assertThat(processed.processedAt).isEqualTo(PROCESSED_AT)

        val notification = notificationRequestRepository.findAll().single()
        assertThat(notification.sourceEventId).isEqualTo(EVENT_ID)
        assertThat(notification.reservationId).isEqualTo(RESERVATION_ID)
        assertThat(notification.memberId).isEqualTo(MEMBER_ID)
        assertThat(notification.notificationType).isEqualTo(ReservationNotificationType.RESERVATION_CONFIRMED)
        assertThat(notification.status).isEqualTo(ReservationNotificationStatus.READY)
        assertThat(notification.createdAt).isEqualTo(PROCESSED_AT)
    }

    @Test
    fun `canceled reservation creates a terminal skipped stale result that redelivery does not change`() {
        saveReservation(canceled = true)

        assertThat(handler.handle(command())).isEqualTo(ReservationNotificationHandlingResult.PROCESSED)
        assertThat(handler.handle(command())).isEqualTo(ReservationNotificationHandlingResult.DUPLICATE)

        assertThat(processedEventRepository.count()).isEqualTo(1)
        val notifications = notificationRequestRepository.findAll()
        assertThat(notifications).hasSize(1)
        assertThat(notifications.single().status).isEqualTo(ReservationNotificationStatus.SKIPPED_STALE)
        assertThat(notifications).noneMatch { it.status == ReservationNotificationStatus.READY }
    }

    @Test
    fun `sequential duplicate returns no-op without another business result`() {
        saveReservation(canceled = false)

        val first = handler.handle(command())
        val second = handler.handle(command())

        assertThat(first).isEqualTo(ReservationNotificationHandlingResult.PROCESSED)
        assertThat(second).isEqualTo(ReservationNotificationHandlingResult.DUPLICATE)
        assertThat(processedEventRepository.count()).isEqualTo(1)
        assertThat(notificationRequestRepository.count()).isEqualTo(1)
    }

    @Test
    fun `concurrent duplicate transactions use the PostgreSQL primary key and create one business result`() {
        saveReservation(canceled = false)

        val results = TestConcurrencyUtils.runConcurrently(
            { handler.handle(command()) },
            { handler.handle(command()) },
        )

        assertThat(results)
            .containsExactlyInAnyOrder(
                ReservationNotificationHandlingResult.PROCESSED,
                ReservationNotificationHandlingResult.DUPLICATE,
            )
        assertThat(processedEventRepository.count()).isEqualTo(1)
        assertThat(notificationRequestRepository.count()).isEqualTo(1)
    }

    @Test
    fun `missing reservation rolls back processed event and does not return success`() {
        assertThatThrownBy { handler.handle(command()) }
            .isInstanceOf(ReservationNotificationProcessingException::class.java)
            .hasMessageContaining("eventId=$EVENT_ID")

        assertThat(processedEventRepository.count()).isZero()
        assertThat(notificationRequestRepository.count()).isZero()
    }

    @Test
    fun `source event unique constraint independently rolls back a new processed insert`() {
        saveReservation(canceled = false)
        notificationRequestRepository.saveAndFlush(
            ReservationNotificationRequestJpaEntity(
                sourceEventId = EVENT_ID,
                reservationId = RESERVATION_ID,
                memberId = MEMBER_ID,
                notificationType = ReservationNotificationType.RESERVATION_CONFIRMED,
                status = ReservationNotificationStatus.READY,
                createdAt = PROCESSED_AT.minusSeconds(1),
            ),
        )

        assertThatThrownBy { handler.handle(command()) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(processedEventRepository.count()).isZero()
        assertThat(notificationRequestRepository.count()).isEqualTo(1)
    }

    @ParameterizedTest
    @EnumSource(IdentityMismatch::class)
    fun `reservation identity mismatch rolls back both consumer tables`(mismatch: IdentityMismatch) {
        saveReservation(canceled = false)
        val invalidCommand = when (mismatch) {
            IdentityMismatch.MEMBER -> command().copy(memberId = MEMBER_ID + 1)
            IdentityMismatch.CLASS_SESSION -> command().copy(classSessionId = CLASS_SESSION_ID + 1)
            IdentityMismatch.MEMBER_PASS -> command().copy(memberPassId = MEMBER_PASS_ID + 1)
        }

        assertThatThrownBy { handler.handle(invalidCommand) }
            .isInstanceOf(ReservationNotificationProcessingException::class.java)
            .hasMessageContaining("eventId=$EVENT_ID")

        assertThat(processedEventRepository.count()).isZero()
        assertThat(notificationRequestRepository.count()).isZero()
    }

    private fun command(): ReservationConfirmedNotificationCommand =
        ReservationConfirmedNotificationCommand(
            eventId = EVENT_ID,
            reservationId = RESERVATION_ID,
            memberId = MEMBER_ID,
            classSessionId = CLASS_SESSION_ID,
            memberPassId = MEMBER_PASS_ID,
        )

    private fun saveReservation(canceled: Boolean) {
        jdbcTemplate.update(
            """
            insert into members (id, name, phone, email, status, created_at, updated_at)
            values (?, 'Consumer Test Member', '01012345678', null, 'ACTIVE', ?, ?)
            """.trimIndent(),
            MEMBER_ID,
            RESERVED_AT.toSqlTimestamp(),
            RESERVED_AT.toSqlTimestamp(),
        )
        jdbcTemplate.update(
            """
            insert into pass_products (id, name, type, total_count, valid_days, created_at, updated_at)
            values (?, 'Consumer Test Pass', 'COUNT_PASS', 10, 90, ?, ?)
            """.trimIndent(),
            PASS_PRODUCT_ID,
            RESERVED_AT.toSqlTimestamp(),
            RESERVED_AT.toSqlTimestamp(),
        )
        jdbcTemplate.update(
            """
            insert into class_sessions
                (id, title, starts_at, ends_at, capacity, reserved_count, status, created_at, updated_at)
            values (?, 'Consumer Test Class', ?, ?, 10, 1, 'OPEN', ?, ?)
            """.trimIndent(),
            CLASS_SESSION_ID,
            RESERVED_AT.plusSeconds(3600).toSqlTimestamp(),
            RESERVED_AT.plusSeconds(7200).toSqlTimestamp(),
            RESERVED_AT.toSqlTimestamp(),
            RESERVED_AT.toSqlTimestamp(),
        )
        jdbcTemplate.update(
            """
            insert into member_passes
                (id, member_id, pass_product_id, product_name_snapshot, pass_type_snapshot,
                 total_count, remaining_count, valid_days_snapshot, status, issued_at, expires_at,
                 version, created_at, updated_at)
            values (?, ?, ?, 'Consumer Test Pass', 'COUNT_PASS', 10, 9, 90, 'ACTIVE', ?, ?, 0, ?, ?)
            """.trimIndent(),
            MEMBER_PASS_ID,
            MEMBER_ID,
            PASS_PRODUCT_ID,
            RESERVED_AT.minusSeconds(3600).toSqlTimestamp(),
            RESERVED_AT.plusSeconds(7_776_000).toSqlTimestamp(),
            RESERVED_AT.toSqlTimestamp(),
            RESERVED_AT.toSqlTimestamp(),
        )
        if (canceled) {
            jdbcTemplate.update(
                """
                insert into reservations
                    (id, member_id, class_session_id, member_pass_id, status, reserved_at,
                     canceled_at, cancel_reason, created_at, updated_at)
                values (?, ?, ?, ?, 'CANCELED', ?, ?, 'USER_REQUESTED', ?, ?)
                """.trimIndent(),
                RESERVATION_ID,
                MEMBER_ID,
                CLASS_SESSION_ID,
                MEMBER_PASS_ID,
                RESERVED_AT.toSqlTimestamp(),
                RESERVED_AT.plusSeconds(60).toSqlTimestamp(),
                RESERVED_AT.toSqlTimestamp(),
                RESERVED_AT.plusSeconds(60).toSqlTimestamp(),
            )
        } else {
            jdbcTemplate.update(
                """
                insert into reservations
                    (id, member_id, class_session_id, member_pass_id, status, reserved_at, created_at, updated_at)
                values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)
                """.trimIndent(),
                RESERVATION_ID,
                MEMBER_ID,
                CLASS_SESSION_ID,
                MEMBER_PASS_ID,
                RESERVED_AT.toSqlTimestamp(),
                RESERVED_AT.toSqlTimestamp(),
                RESERVED_AT.toSqlTimestamp(),
            )
        }
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

    enum class IdentityMismatch {
        MEMBER,
        CLASS_SESSION,
        MEMBER_PASS,
    }

    @TestConfiguration
    class FixedClockConfiguration {
        @Bean
        fun fixedClock(): Clock = Clock.fixed(PROCESSED_AT, ZoneOffset.UTC)
    }

    companion object {
        const val EVENT_ID = 101L
        const val RESERVATION_ID = 201L
        const val MEMBER_ID = 301L
        const val CLASS_SESSION_ID = 401L
        const val MEMBER_PASS_ID = 501L
        const val PASS_PRODUCT_ID = 601L
        val RESERVED_AT: Instant = Instant.parse("2026-08-15T00:00:00Z")
        val PROCESSED_AT: Instant = Instant.parse("2026-08-16T00:00:00Z")
    }
}

private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)
