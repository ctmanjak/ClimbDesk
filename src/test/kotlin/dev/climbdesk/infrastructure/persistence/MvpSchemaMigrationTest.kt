package dev.climbdesk.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@JdbcTest(
    properties = [
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MvpSchemaMigrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `flyway baseline creates all mvp tables`() {
        val tableNames = jdbcTemplate.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = 'public'
            """.trimIndent(),
            String::class.java,
        )

        assertThat(tableNames).contains(
            "admin_users",
            "members",
            "pass_products",
            "class_sessions",
            "member_passes",
            "reservations",
            "pass_usage_histories",
            "outbox_events",
            "flyway_schema_history",
        )
    }

    @Test
    fun `flyway baseline creates m3 critical constraints and indexes`() {
        val constraintNames = jdbcTemplate.queryForList(
            """
            select conname
            from pg_constraint
            where connamespace = 'public'::regnamespace
            """.trimIndent(),
            String::class.java,
        )
        val indexNames = jdbcTemplate.queryForList(
            """
            select indexname
            from pg_indexes
            where schemaname = 'public'
            """.trimIndent(),
            String::class.java,
        )

        assertThat(constraintNames).contains(
            "ck_member_passes_count_range",
            "fk_member_passes_member",
            "fk_member_passes_pass_product",
            "ck_class_sessions_reserved_count",
            "fk_reservations_member_pass",
            "ck_pass_usage_histories_changed_count",
        )
        assertThat(indexNames).contains(
            "idx_member_passes_available_selection",
            "uk_reservations_confirmed_member_class",
            "idx_outbox_events_pending",
        )
    }

    @Test
    fun `flyway baseline enforces member deactivation field consistency`() {
        assertThat(
            jdbcTemplate.update(
                """
                insert into members (name, phone, status, created_at, updated_at)
                values ('Active Member', '010-0000-0001', 'ACTIVE', now(), now())
                """.trimIndent(),
            ),
        ).isEqualTo(1)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                insert into members (name, phone, status, created_at, updated_at, deactivated_at)
                values ('Invalid Active Member', '010-0000-0002', 'ACTIVE', now(), now(), now())
                """.trimIndent(),
            )
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `flyway baseline enforces class session cancellation field consistency`() {
        assertThat(
            jdbcTemplate.update(
                """
                insert into class_sessions (
                  title, starts_at, ends_at, capacity, reserved_count, status,
                  created_at, updated_at
                )
                values (
                  'Open Class', now(), now() + interval '1 hour', 10, 0, 'OPEN',
                  now(), now()
                )
                """.trimIndent(),
            ),
        ).isEqualTo(1)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                insert into class_sessions (
                  title, starts_at, ends_at, capacity, reserved_count, status,
                  created_at, updated_at, canceled_at, cancel_reason
                )
                values (
                  'Invalid Open Class', now(), now() + interval '1 hour', 10, 0, 'OPEN',
                  now(), now(), now(), 'not canceled'
                )
                """.trimIndent(),
            )
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `flyway baseline enforces outbox published field consistency`() {
        assertThat(
            jdbcTemplate.update(
                """
                insert into outbox_events (
                  event_type, aggregate_type, aggregate_id, payload, status, retry_count,
                  occurred_at, created_at, updated_at
                )
                values (
                  'TestEvent', 'TestAggregate', 1, '{}'::jsonb, 'PENDING', 0,
                  now(), now(), now()
                )
                """.trimIndent(),
            ),
        ).isEqualTo(1)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                insert into outbox_events (
                  event_type, aggregate_type, aggregate_id, payload, status, retry_count,
                  occurred_at, published_at, created_at, updated_at
                )
                values (
                  'InvalidEvent', 'TestAggregate', 1, '{}'::jsonb, 'PENDING', 0,
                  now(), now(), now(), now()
                )
                """.trimIndent(),
            )
        }.isInstanceOf(DataAccessException::class.java)
    }
}
