package dev.climbdesk.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
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
}
