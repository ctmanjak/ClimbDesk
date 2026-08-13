package dev.climbdesk.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import javax.sql.DataSource

@Testcontainers(disabledWithoutDocker = true)
@JdbcTest(
    properties = [
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///event-contract-schema",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.flyway.enabled=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EventContractSchemaMigrationTest @Autowired constructor(
    private val dataSource: DataSource,
) {
    @BeforeEach
    fun setUp() {
        flyway().clean()
        flyway("2").migrate()
    }

    @Test
    fun `migration keeps existing outbox rows non-publishable as schema version one`() {
        val eventId = executeReturningId(
            """
            insert into outbox_events (
              event_type, aggregate_type, aggregate_id, payload, status, retry_count,
              occurred_at, created_at, updated_at
            )
            values (
              'ReservationConfirmedEvent', 'Reservation', 101, '{}'::jsonb, 'PENDING', 0,
              now(), now(), now()
            )
            returning id
            """,
        )

        flyway().migrate()

        connection().use { connection ->
            connection.prepareStatement(
                """
                select publish_target, schema_version, last_error
                from outbox_events
                where id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, eventId)
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("publish_target")).isEqualTo("NONE")
                    assertThat(result.getInt("schema_version")).isEqualTo(1)
                    assertThat(result.getString("last_error")).isNull()
                }
            }

            connection.prepareStatement(
                """
                select conname, convalidated
                from pg_constraint
                where conname in (
                  'ck_outbox_events_publish_target',
                  'ck_outbox_events_schema_version'
                )
                order by conname
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("conname")).isEqualTo("ck_outbox_events_publish_target")
                    assertThat(result.getBoolean("convalidated")).isTrue()
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("conname")).isEqualTo("ck_outbox_events_schema_version")
                    assertThat(result.getBoolean("convalidated")).isTrue()
                    assertThat(result.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `migration enforces outbox publish target and schema version constraints`() {
        flyway().migrate()

        assertConstraintRejects(
            """
            insert into outbox_events (
              event_type, aggregate_type, aggregate_id, payload, status, retry_count,
              occurred_at, created_at, updated_at, publish_target
            )
            values (
              'TestEvent', 'TestAggregate', 1, '{}'::jsonb, 'PENDING', 0,
              now(), now(), now(), 'KAFKA'
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into outbox_events (
              event_type, aggregate_type, aggregate_id, payload, status, retry_count,
              occurred_at, created_at, updated_at, schema_version
            )
            values (
              'TestEvent', 'TestAggregate', 1, '{}'::jsonb, 'PENDING', 0,
              now(), now(), now(), 0
            )
            """,
        )
    }

    @Test
    fun `migration enforces processed event idempotency`() {
        flyway().migrate()
        execute(
            """
            insert into processed_events (consumer_name, event_id, event_type, processed_at)
            values ('reservation-notification-v1', 1001, 'reservation.confirmed', now())
            """,
        )

        assertUniqueConstraintRejects(
            """
            insert into processed_events (consumer_name, event_id, event_type, processed_at)
            values ('reservation-notification-v1', 1001, 'reservation.confirmed', now())
            """,
        )
    }

    @Test
    fun `migration enforces notification source event uniqueness and enum values`() {
        flyway().migrate()
        execute(
            """
            insert into reservation_notification_requests (
              source_event_id, reservation_id, member_id, notification_type, status, created_at
            )
            values (1001, 101, 201, 'RESERVATION_CONFIRMED', 'READY', now())
            """,
        )

        assertUniqueConstraintRejects(
            """
            insert into reservation_notification_requests (
              source_event_id, reservation_id, member_id, notification_type, status, created_at
            )
            values (1001, 102, 202, 'RESERVATION_CONFIRMED', 'SKIPPED_STALE', now())
            """,
        )
        assertConstraintRejects(
            """
            insert into reservation_notification_requests (
              source_event_id, reservation_id, member_id, notification_type, status, created_at
            )
            values (1002, 101, 201, 'RESERVATION_CANCELED', 'READY', now())
            """,
        )
        assertConstraintRejects(
            """
            insert into reservation_notification_requests (
              source_event_id, reservation_id, member_id, notification_type, status, created_at
            )
            values (1003, 101, 201, 'RESERVATION_CONFIRMED', 'SENT', now())
            """,
        )
    }

    private fun assertConstraintRejects(sql: String) {
        assertThatThrownBy { execute(sql) }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("violates check constraint")
    }

    private fun assertUniqueConstraintRejects(sql: String) {
        assertThatThrownBy { execute(sql) }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("duplicate key value violates unique constraint")
    }

    private fun execute(sql: String) {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(sql.trimIndent())
            }
        }
    }

    private fun executeReturningId(sql: String): Long =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql.trimIndent()).use { result ->
                    check(result.next())
                    result.getLong("id")
                }
            }
        }

    private fun connection() =
        dataSource.connection

    private fun flyway(target: String? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(dataSource)
            .cleanDisabled(false)
            .configuration(
                mapOf("flyway.postgresql.transactional.lock" to "false"),
            )
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }
}
