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
            values ('consumer-a', 1001, 'reservation.confirmed', now())
            """,
        )
        execute(
            """
            insert into processed_events (consumer_name, event_id, event_type, processed_at)
            values ('consumer-b', 1001, 'reservation.confirmed', now())
            """,
        )

        assertUniqueConstraintRejects(
            """
            insert into processed_events (consumer_name, event_id, event_type, processed_at)
            values ('consumer-a', 1001, 'reservation.confirmed', now())
            """,
        )

        assertThat(processedEventRows(1001)).containsExactly(
            ProcessedEventRow("consumer-a", 1001),
            ProcessedEventRow("consumer-b", 1001),
        )
        assertThat(primaryKeyColumns("processed_events"))
            .containsExactly("consumer_name", "event_id")
    }

    @Test
    fun `migration creates the approved MQ column contracts`() {
        flyway().migrate()

        assertThat(columnContracts("outbox_events").filter { it.name in outboxMqColumnNames })
            .containsExactly(
                ColumnContract("publish_target", "character varying(30)", true, "'NONE'::character varying"),
                ColumnContract("schema_version", "integer", true, "1"),
                ColumnContract("last_error", "character varying(1000)", false, null),
            )
        assertThat(columnContracts("processed_events"))
            .containsExactly(
                ColumnContract("consumer_name", "character varying(100)", true, null),
                ColumnContract("event_id", "bigint", true, null),
                ColumnContract("event_type", "character varying(100)", true, null),
                ColumnContract("processed_at", "timestamp with time zone", true, null),
            )
        assertThat(columnContracts("reservation_notification_requests"))
            .containsExactly(
                ColumnContract("id", "bigint", true, null, "BY DEFAULT"),
                ColumnContract("source_event_id", "bigint", true, null),
                ColumnContract("reservation_id", "bigint", true, null),
                ColumnContract("member_id", "bigint", true, null),
                ColumnContract("notification_type", "character varying(40)", true, null),
                ColumnContract("status", "character varying(30)", true, null),
                ColumnContract("created_at", "timestamp with time zone", true, null),
            )
        assertThat(uniqueConstraintColumns("reservation_notification_requests"))
            .containsExactly("source_event_id")
    }

    @Test
    fun `publishable index migration remains explicitly nontransactional`() {
        flyway().migrate()

        val migrationConfiguration =
            checkNotNull(javaClass.classLoader.getResourceAsStream(V4_CONFIGURATION)) {
                "Missing $V4_CONFIGURATION"
            }.bufferedReader().use { it.readText().trim() }

        assertThat(migrationConfiguration).isEqualTo("executeInTransaction=false")
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
            .isInstanceOfSatisfying(SQLException::class.java) { exception ->
                assertThat(exception.sqlState).isEqualTo(CHECK_VIOLATION_SQL_STATE)
                assertThat(exception.message).contains("violates check constraint")
            }
    }

    private fun assertUniqueConstraintRejects(sql: String) {
        assertThatThrownBy { execute(sql) }
            .isInstanceOfSatisfying(SQLException::class.java) { exception ->
                assertThat(exception.sqlState).isEqualTo(UNIQUE_VIOLATION_SQL_STATE)
                assertThat(exception.message).contains("duplicate key value violates unique constraint")
            }
    }

    private fun processedEventRows(eventId: Long): List<ProcessedEventRow> =
        connection().use { connection ->
            connection.prepareStatement(
                """
                select consumer_name, event_id
                from processed_events
                where event_id = ?
                order by consumer_name
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, eventId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(ProcessedEventRow(result.getString("consumer_name"), result.getLong("event_id")))
                        }
                    }
                }
            }
        }

    private fun columnContracts(tableName: String): List<ColumnContract> =
        connection().use { connection ->
            connection.prepareStatement(
                """
                select
                  a.attname as column_name,
                  format_type(a.atttypid, a.atttypmod) as sql_type,
                  a.attnotnull,
                  pg_get_expr(d.adbin, d.adrelid) as default_expression,
                  case a.attidentity
                    when 'a' then 'ALWAYS'
                    when 'd' then 'BY DEFAULT'
                    else null
                  end as identity_generation
                from pg_attribute a
                join pg_class t on t.oid = a.attrelid
                join pg_namespace n on n.oid = t.relnamespace
                left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                where n.nspname = 'public'
                  and t.relname = ?
                  and a.attnum > 0
                  and not a.attisdropped
                order by a.attnum
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tableName)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                ColumnContract(
                                    name = result.getString("column_name"),
                                    sqlType = result.getString("sql_type"),
                                    notNull = result.getBoolean("attnotnull"),
                                    defaultExpression = result.getString("default_expression"),
                                    identityGeneration = result.getString("identity_generation"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun primaryKeyColumns(tableName: String): List<String> =
        constraintColumns(tableName, "p")

    private fun uniqueConstraintColumns(tableName: String): List<String> =
        constraintColumns(tableName, "u")

    private fun constraintColumns(
        tableName: String,
        constraintType: String,
    ): List<String> =
        connection().use { connection ->
            connection.prepareStatement(
                """
                select a.attname
                from pg_constraint c
                join pg_class t on t.oid = c.conrelid
                join pg_namespace n on n.oid = t.relnamespace
                join pg_index i on i.indexrelid = c.conindid
                cross join lateral unnest(i.indkey) with ordinality as key_column(attnum, position)
                join pg_attribute a on a.attrelid = t.oid and a.attnum = key_column.attnum
                where n.nspname = 'public'
                  and t.relname = ?
                  and c.contype = ?::"char"
                order by key_column.position
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tableName)
                statement.setString(2, constraintType)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.getString("attname"))
                        }
                    }
                }
            }
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

    private data class ProcessedEventRow(
        val consumerName: String,
        val eventId: Long,
    )

    private data class ColumnContract(
        val name: String,
        val sqlType: String,
        val notNull: Boolean,
        val defaultExpression: String?,
        val identityGeneration: String? = null,
    )

    private companion object {
        val outboxMqColumnNames = setOf("publish_target", "schema_version", "last_error")
        const val CHECK_VIOLATION_SQL_STATE = "23514"
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        const val V4_CONFIGURATION = "db/migration/V4__create_outbox_publishable_index.sql.conf"
    }
}
