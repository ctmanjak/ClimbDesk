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
}
