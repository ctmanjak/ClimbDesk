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
    fun `flyway baseline creates documented constraint inventory`() {
        val constraints = jdbcTemplate.query(
            """
            select
              c.conname,
              t.relname as table_name,
              c.contype
            from pg_constraint c
            join pg_class t on t.oid = c.conrelid
            where c.connamespace = 'public'::regnamespace
              and c.contype in ('c', 'f', 'u')
            """.trimIndent(),
        ) { rs, _ ->
            ConstraintInventory(
                name = rs.getString("conname"),
                tableName = rs.getString("table_name"),
                type = rs.getString("contype"),
            )
        }

        assertThat(constraints).containsExactlyInAnyOrder(
            ConstraintInventory("uk_admin_users_email", "admin_users", "u"),
            ConstraintInventory("ck_admin_users_role", "admin_users", "c"),
            ConstraintInventory("ck_admin_users_status", "admin_users", "c"),
            ConstraintInventory("uk_members_phone", "members", "u"),
            ConstraintInventory("ck_members_status", "members", "c"),
            ConstraintInventory("ck_members_deactivated_at", "members", "c"),
            ConstraintInventory("ck_pass_products_type", "pass_products", "c"),
            ConstraintInventory("ck_pass_products_total_count", "pass_products", "c"),
            ConstraintInventory("ck_pass_products_price", "pass_products", "c"),
            ConstraintInventory("ck_pass_products_valid_days", "pass_products", "c"),
            ConstraintInventory("ck_class_sessions_status", "class_sessions", "c"),
            ConstraintInventory("ck_class_sessions_time_range", "class_sessions", "c"),
            ConstraintInventory("ck_class_sessions_capacity", "class_sessions", "c"),
            ConstraintInventory("ck_class_sessions_reserved_count", "class_sessions", "c"),
            ConstraintInventory("ck_class_sessions_affected_reservation_count", "class_sessions", "c"),
            ConstraintInventory("ck_class_sessions_cancel_fields", "class_sessions", "c"),
            ConstraintInventory("fk_member_passes_member", "member_passes", "f"),
            ConstraintInventory("fk_member_passes_pass_product", "member_passes", "f"),
            ConstraintInventory("ck_member_passes_status", "member_passes", "c"),
            ConstraintInventory("ck_member_passes_count_range", "member_passes", "c"),
            ConstraintInventory("ck_member_passes_version", "member_passes", "c"),
            ConstraintInventory("ck_member_passes_valid_days_snapshot", "member_passes", "c"),
            ConstraintInventory("ck_member_passes_expires_after_issued", "member_passes", "c"),
            ConstraintInventory("fk_reservations_member", "reservations", "f"),
            ConstraintInventory("fk_reservations_class_session", "reservations", "f"),
            ConstraintInventory("fk_reservations_member_pass", "reservations", "f"),
            ConstraintInventory("ck_reservations_status", "reservations", "c"),
            ConstraintInventory("ck_reservations_cancel_reason", "reservations", "c"),
            ConstraintInventory("ck_reservations_cancel_fields", "reservations", "c"),
            ConstraintInventory("fk_pass_usage_histories_member_pass", "pass_usage_histories", "f"),
            ConstraintInventory("fk_pass_usage_histories_reservation", "pass_usage_histories", "f"),
            ConstraintInventory("ck_pass_usage_histories_type", "pass_usage_histories", "c"),
            ConstraintInventory("ck_pass_usage_histories_reason", "pass_usage_histories", "c"),
            ConstraintInventory("ck_pass_usage_histories_changed_count", "pass_usage_histories", "c"),
            ConstraintInventory("ck_pass_usage_histories_remaining_count_after", "pass_usage_histories", "c"),
            ConstraintInventory("ck_outbox_events_status", "outbox_events", "c"),
            ConstraintInventory("ck_outbox_events_retry_count", "outbox_events", "c"),
            ConstraintInventory("ck_outbox_events_published_at", "outbox_events", "c"),
        )
    }

    @Test
    fun `flyway baseline creates documented supporting index inventory`() {
        val indexes = jdbcTemplate.query(
            """
            select
              ic.relname as index_name,
              tc.relname as table_name,
              i.indisunique,
              array(
                select
                  a.attname
                  || case when (key_option.option & 1) = 1 then ' DESC' else '' end
                  || case when (key_option.option & 2) = 2 then ' NULLS FIRST' else '' end
                from unnest(i.indkey) with ordinality as key_position(attnum, ordinality)
                join unnest(i.indoption) with ordinality as key_option(option, ordinality)
                  on key_option.ordinality = key_position.ordinality
                join pg_attribute a on a.attrelid = i.indrelid and a.attnum = key_position.attnum
                where key_position.attnum <> 0
                order by key_position.ordinality
              ) as key_columns,
              pg_get_expr(i.indpred, i.indrelid) as predicate
            from pg_index i
            join pg_class ic on ic.oid = i.indexrelid
            join pg_class tc on tc.oid = i.indrelid
            join pg_namespace n on n.oid = tc.relnamespace
            where n.nspname = 'public'
              and tc.relname <> 'flyway_schema_history'
              and not exists (
                select 1
                from pg_constraint c
                where c.conindid = i.indexrelid
              )
            """.trimIndent(),
        ) { rs, _ ->
            IndexInventory(
                name = rs.getString("index_name"),
                tableName = rs.getString("table_name"),
                isUnique = rs.getBoolean("indisunique"),
                keyColumns = (rs.getArray("key_columns").array as Array<*>).map { it as String },
                predicate = rs.getString("predicate"),
            )
        }

        assertThat(indexes).containsExactlyInAnyOrder(
            index("idx_admin_users_status_role", "admin_users", listOf("status", "role")),
            index(
                "idx_members_created_at_id",
                "members",
                listOf("created_at DESC NULLS FIRST", "id DESC NULLS FIRST"),
            ),
            index("idx_members_status", "members", listOf("status")),
            index(
                "idx_pass_products_created_at_id",
                "pass_products",
                listOf("created_at DESC NULLS FIRST", "id DESC NULLS FIRST"),
            ),
            index(
                "idx_class_sessions_starts_at_id",
                "class_sessions",
                listOf("starts_at DESC NULLS FIRST", "id DESC NULLS FIRST"),
            ),
            index(
                "idx_class_sessions_status_starts_at",
                "class_sessions",
                listOf("status", "starts_at"),
            ),
            index("idx_member_passes_member_id", "member_passes", listOf("member_id")),
            index(
                "idx_member_passes_member_status",
                "member_passes",
                listOf("member_id", "status"),
            ),
            index(
                "idx_member_passes_available_selection",
                "member_passes",
                listOf("member_id", "status", "expires_at", "issued_at", "id"),
                predicate = "(((status)::text = 'ACTIVE'::text) AND (remaining_count > 0))",
            ),
            index(
                "uk_reservations_confirmed_member_class",
                "reservations",
                listOf("member_id", "class_session_id"),
                isUnique = true,
                predicate = "((status)::text = 'CONFIRMED'::text)",
            ),
            index(
                "idx_reservations_member_reserved_at",
                "reservations",
                listOf("member_id", "reserved_at DESC NULLS FIRST", "id DESC NULLS FIRST"),
            ),
            index(
                "idx_reservations_class_session_status",
                "reservations",
                listOf("class_session_id", "status"),
            ),
            index("idx_reservations_member_pass_id", "reservations", listOf("member_pass_id")),
            index(
                "idx_reservations_status_reserved_at",
                "reservations",
                listOf("status", "reserved_at DESC NULLS FIRST"),
            ),
            index(
                "idx_pass_usage_histories_member_pass_created_at",
                "pass_usage_histories",
                listOf("member_pass_id", "created_at DESC NULLS FIRST", "id DESC NULLS FIRST"),
            ),
            index(
                "idx_pass_usage_histories_reservation_id",
                "pass_usage_histories",
                listOf("reservation_id"),
            ),
            index(
                "idx_outbox_events_pending",
                "outbox_events",
                listOf("status", "next_retry_at NULLS FIRST", "id"),
                predicate = """
                    ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'FAILED'::character varying])::text[]))
                """.trimIndent(),
            ),
            index(
                "idx_outbox_events_aggregate",
                "outbox_events",
                listOf("aggregate_type", "aggregate_id"),
            ),
            index(
                "idx_outbox_events_occurred_at",
                "outbox_events",
                listOf("occurred_at DESC NULLS FIRST", "id DESC NULLS FIRST"),
            ),
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
    fun `flyway baseline enforces member status constraint`() {
        assertConstraintRejects(
            """
            insert into members (name, phone, status, created_at, updated_at)
            values ('Invalid Member', '010-0000-0003', 'SUSPENDED', now(), now())
            """,
        )
    }

    @Test
    fun `flyway baseline enforces member pass count and lifecycle constraints`() {
        val memberId = insertMember("010-1000-0001")
        val passProductId = insertPassProduct()

        assertThat(insertMemberPass(memberId, passProductId)).isPositive()

        assertConstraintRejects(
            """
            insert into member_passes (
              member_id, pass_product_id, product_name_snapshot, pass_type_snapshot,
              total_count, remaining_count, status, issued_at, version, created_at, updated_at
            )
            values (
              $memberId, $passProductId, 'Count Pass', 'COUNT_PASS',
              0, 0, 'ACTIVE', now(), 0, now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into member_passes (
              member_id, pass_product_id, product_name_snapshot, pass_type_snapshot,
              total_count, remaining_count, status, issued_at, version, created_at, updated_at
            )
            values (
              $memberId, $passProductId, 'Count Pass', 'COUNT_PASS',
              10, -1, 'ACTIVE', now(), 0, now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into member_passes (
              member_id, pass_product_id, product_name_snapshot, pass_type_snapshot,
              total_count, remaining_count, status, issued_at, version, created_at, updated_at
            )
            values (
              $memberId, $passProductId, 'Count Pass', 'COUNT_PASS',
              10, 11, 'ACTIVE', now(), 0, now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into member_passes (
              member_id, pass_product_id, product_name_snapshot, pass_type_snapshot,
              total_count, remaining_count, status, issued_at, version, created_at, updated_at
            )
            values (
              $memberId, $passProductId, 'Count Pass', 'COUNT_PASS',
              10, 10, 'ACTIVE', now(), -1, now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into member_passes (
              member_id, pass_product_id, product_name_snapshot, pass_type_snapshot,
              total_count, remaining_count, status, issued_at, expires_at, version,
              created_at, updated_at
            )
            values (
              $memberId, $passProductId, 'Count Pass', 'COUNT_PASS',
              10, 10, 'ACTIVE', now(), now(), 0, now(), now()
            )
            """,
        )
    }

    @Test
    fun `flyway baseline enforces class session time capacity and reserved count constraints`() {
        assertThat(insertClassSession()).isPositive()

        assertConstraintRejects(
            """
            insert into class_sessions (
              title, starts_at, ends_at, capacity, reserved_count, status,
              created_at, updated_at
            )
            values (
              'Invalid Time Class', now(), now(), 10, 0, 'OPEN',
              now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into class_sessions (
              title, starts_at, ends_at, capacity, reserved_count, status,
              created_at, updated_at
            )
            values (
              'Invalid Capacity Class', now(), now() + interval '1 hour', 0, 0, 'OPEN',
              now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into class_sessions (
              title, starts_at, ends_at, capacity, reserved_count, status,
              created_at, updated_at
            )
            values (
              'Invalid Reserved Class', now(), now() + interval '1 hour', 10, -1, 'OPEN',
              now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into class_sessions (
              title, starts_at, ends_at, capacity, reserved_count, status,
              created_at, updated_at
            )
            values (
              'Over Reserved Class', now(), now() + interval '1 hour', 10, 11, 'OPEN',
              now(), now()
            )
            """,
        )
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
    fun `flyway baseline enforces reservation cancel field constraints`() {
        val reservationIds = insertReservationFixtures()
        val confirmedConstraintIds = insertReservationFixtures()

        assertThat(insertReservation(reservationIds.memberId, reservationIds.classSessionId, reservationIds.memberPassId))
            .isPositive()

        assertConstraintRejects(
            """
            insert into reservations (
              member_id, class_session_id, member_pass_id, status, reserved_at,
              canceled_at, created_at, updated_at
            )
            values (
              ${confirmedConstraintIds.memberId},
              ${confirmedConstraintIds.classSessionId},
              ${confirmedConstraintIds.memberPassId},
              'CONFIRMED', now(), now(), now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into reservations (
              member_id, class_session_id, member_pass_id, status, reserved_at,
              canceled_at, created_at, updated_at
            )
            values (
              ${reservationIds.memberId}, ${reservationIds.classSessionId}, ${reservationIds.memberPassId},
              'CANCELED', now(), now(), now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into reservations (
              member_id, class_session_id, member_pass_id, status, reserved_at,
              canceled_at, cancel_reason, created_at, updated_at
            )
            values (
              ${reservationIds.memberId}, ${reservationIds.classSessionId}, ${reservationIds.memberPassId},
              'CANCELED', now(), now(), 'NO_SHOW', now(), now()
            )
            """,
        )
    }

    @Test
    fun `flyway baseline enforces pass usage history reason and count constraints`() {
        val reservationIds = insertReservationFixtures()
        val reservationId = insertReservation(
            reservationIds.memberId,
            reservationIds.classSessionId,
            reservationIds.memberPassId,
        )

        assertThat(insertPassUsageHistory(reservationIds.memberPassId, reservationId)).isPositive()

        assertConstraintRejects(
            """
            insert into pass_usage_histories (
              member_pass_id, reservation_id, type, reason, changed_count,
              remaining_count_after, created_at
            )
            values (
              ${reservationIds.memberPassId}, $reservationId, 'CONSUME', 'NO_SHOW', -1, 9, now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into pass_usage_histories (
              member_pass_id, reservation_id, type, reason, changed_count,
              remaining_count_after, created_at
            )
            values (
              ${reservationIds.memberPassId}, $reservationId, 'CONSUME', 'RESERVATION_CONFIRMED', 1, 9, now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into pass_usage_histories (
              member_pass_id, reservation_id, type, reason, changed_count,
              remaining_count_after, created_at
            )
            values (
              ${reservationIds.memberPassId}, $reservationId, 'RESTORE', 'RESERVATION_CANCELED', -1, 10, now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into pass_usage_histories (
              member_pass_id, reservation_id, type, reason, changed_count,
              remaining_count_after, created_at
            )
            values (
              ${reservationIds.memberPassId}, $reservationId, 'CONSUME', 'RESERVATION_CONFIRMED', -1, -1, now()
            )
            """,
        )
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

    @Test
    fun `flyway baseline enforces outbox status and retry constraints`() {
        assertThat(insertOutboxEvent()).isPositive()

        assertConstraintRejects(
            """
            insert into outbox_events (
              event_type, aggregate_type, aggregate_id, payload, status, retry_count,
              occurred_at, created_at, updated_at
            )
            values (
              'InvalidStatusEvent', 'TestAggregate', 1, '{}'::jsonb, 'SENT', 0,
              now(), now(), now()
            )
            """,
        )
        assertConstraintRejects(
            """
            insert into outbox_events (
              event_type, aggregate_type, aggregate_id, payload, status, retry_count,
              occurred_at, created_at, updated_at
            )
            values (
              'InvalidRetryEvent', 'TestAggregate', 1, '{}'::jsonb, 'PENDING', -1,
              now(), now(), now()
            )
            """,
        )
    }

    private fun assertConstraintRejects(sql: String) {
        assertThatThrownBy {
            jdbcTemplate.update(sql.trimIndent())
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun insertReservationFixtures(): ReservationFixtureIds {
        val memberId = insertMember("010-2000-${nextFixtureSuffix()}")
        val passProductId = insertPassProduct()
        val memberPassId = insertMemberPass(memberId, passProductId)
        val classSessionId = insertClassSession()

        return ReservationFixtureIds(
            memberId = memberId,
            classSessionId = classSessionId,
            memberPassId = memberPassId,
        )
    }

    private fun insertMember(phone: String): Long =
        jdbcTemplate.queryForObject(
            """
            insert into members (name, phone, status, created_at, updated_at)
            values ('Fixture Member', '$phone', 'ACTIVE', now(), now())
            returning id
            """.trimIndent(),
            Long::class.java,
        ) ?: error("member id was not returned")

    private fun insertPassProduct(): Long =
        jdbcTemplate.queryForObject(
            """
            insert into pass_products (name, type, total_count, price, valid_days, created_at, updated_at)
            values ('Count Pass', 'COUNT_PASS', 10, 100000, 30, now(), now())
            returning id
            """.trimIndent(),
            Long::class.java,
        ) ?: error("pass product id was not returned")

    private fun insertMemberPass(memberId: Long, passProductId: Long): Long =
        jdbcTemplate.queryForObject(
            """
            insert into member_passes (
              member_id, pass_product_id, product_name_snapshot, pass_type_snapshot,
              total_count, remaining_count, status, issued_at, expires_at, version,
              created_at, updated_at
            )
            values (
              $memberId, $passProductId, 'Count Pass', 'COUNT_PASS',
              10, 10, 'ACTIVE', now(), now() + interval '30 days', 0,
              now(), now()
            )
            returning id
            """.trimIndent(),
            Long::class.java,
        ) ?: error("member pass id was not returned")

    private fun insertClassSession(): Long =
        jdbcTemplate.queryForObject(
            """
            insert into class_sessions (
              title, starts_at, ends_at, capacity, reserved_count, status,
              created_at, updated_at
            )
            values (
              'Fixture Class', now(), now() + interval '1 hour', 10, 0, 'OPEN',
              now(), now()
            )
            returning id
            """.trimIndent(),
            Long::class.java,
        ) ?: error("class session id was not returned")

    private fun insertReservation(
        memberId: Long,
        classSessionId: Long,
        memberPassId: Long,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into reservations (
              member_id, class_session_id, member_pass_id, status, reserved_at,
              created_at, updated_at
            )
            values (
              $memberId, $classSessionId, $memberPassId, 'CONFIRMED', now(),
              now(), now()
            )
            returning id
            """.trimIndent(),
            Long::class.java,
        ) ?: error("reservation id was not returned")

    private fun insertPassUsageHistory(memberPassId: Long, reservationId: Long): Long =
        jdbcTemplate.queryForObject(
            """
            insert into pass_usage_histories (
              member_pass_id, reservation_id, type, reason, changed_count,
              remaining_count_after, created_at
            )
            values (
              $memberPassId, $reservationId, 'CONSUME', 'RESERVATION_CONFIRMED', -1, 9, now()
            )
            returning id
            """.trimIndent(),
            Long::class.java,
        ) ?: error("pass usage history id was not returned")

    private fun insertOutboxEvent(): Long =
        jdbcTemplate.queryForObject(
            """
            insert into outbox_events (
              event_type, aggregate_type, aggregate_id, payload, status, retry_count,
              occurred_at, created_at, updated_at
            )
            values (
              'TestEvent', 'TestAggregate', 1, '{}'::jsonb, 'PENDING', 0,
              now(), now(), now()
            )
            returning id
            """.trimIndent(),
            Long::class.java,
        ) ?: error("outbox event id was not returned")

    private fun nextFixtureSuffix(): String =
        jdbcTemplate.queryForObject("select nextval('members_id_seq')", Long::class.java)
            ?.toString()
            ?: error("fixture suffix was not returned")

    private data class ReservationFixtureIds(
        val memberId: Long,
        val classSessionId: Long,
        val memberPassId: Long,
    )

    private data class ConstraintInventory(
        val name: String,
        val tableName: String,
        val type: String,
    )

    private data class IndexInventory(
        val name: String,
        val tableName: String,
        val isUnique: Boolean,
        val keyColumns: List<String>,
        val predicate: String?,
    )

    private fun index(
        name: String,
        tableName: String,
        keyColumns: List<String>,
        isUnique: Boolean = false,
        predicate: String? = null,
    ): IndexInventory =
        IndexInventory(
            name = name,
            tableName = tableName,
            isUnique = isUnique,
            keyColumns = keyColumns,
            predicate = predicate,
        )
}
