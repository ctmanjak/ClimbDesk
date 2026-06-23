package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
import dev.climbdesk.pass.domain.MemberPassRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassUsageHistoryReason
import dev.climbdesk.pass.domain.PassUsageHistoryType
import dev.climbdesk.pass.domain.PassProductType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
class MemberPassPersistenceAdapterIntegrationTest @Autowired constructor(
    private val memberJpaRepository: MemberJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
    private val memberPassJpaRepository: MemberPassJpaRepository,
    private val passUsageHistoryJpaRepository: PassUsageHistoryJpaRepository,
    private val memberPassRepository: MemberPassRepository,
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

    private fun clearData() {
        passUsageHistoryJpaRepository.deleteAll()
        jdbcTemplate.update("delete from reservations")
        jdbcTemplate.update("delete from class_sessions")
        memberPassJpaRepository.deleteAll()
        passProductJpaRepository.deleteAll()
        memberJpaRepository.deleteAll()
    }

    @Test
    fun `available pass selection ignores unavailable passes`() {
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val member = saveMember()
        val passProduct = savePassProduct()
        saveMemberPass(member = member, passProduct = passProduct, status = MemberPassStatus.EXHAUSTED, remainingCount = 0)
        saveMemberPass(member = member, passProduct = passProduct, status = MemberPassStatus.EXPIRED, remainingCount = 5)
        saveMemberPass(member = member, passProduct = passProduct, status = MemberPassStatus.CANCELED, remainingCount = 5)
        saveMemberPass(member = member, passProduct = passProduct, remainingCount = 0)
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            remainingCount = 5,
            issuedAt = now.minus(20, ChronoUnit.DAYS),
            expiresAt = now.minus(1, ChronoUnit.DAYS),
        )
        val selected = saveMemberPass(
            member = member,
            passProduct = passProduct,
            remainingCount = 5,
            issuedAt = now.minus(1, ChronoUnit.DAYS),
            expiresAt = now.plus(10, ChronoUnit.DAYS),
        )

        val actual = memberPassRepository.findAvailablePassForUse(member.id, now)

        assertThat(actual?.id).isEqualTo(selected.id)
    }

    @Test
    fun `usage result save persists consume history with member pass state change`() {
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val member = saveMember()
        val passProduct = savePassProduct()
        val memberPass = saveMemberPass(member = member, passProduct = passProduct, remainingCount = 10)
        val reservationId = saveReservation(member.id, memberPass.id)

        val saved = memberPassRepository.saveUsageResult(
            memberPass.toDomain().consume(reservationId = reservationId, now = now),
        )

        val updatedMemberPass = memberPassJpaRepository.findById(memberPass.id).orElseThrow()
        val savedHistory = passUsageHistoryJpaRepository.findAll().single()
        assertThat(updatedMemberPass.remainingCount).isEqualTo(9)
        assertThat(saved.usageHistory.id).isEqualTo(savedHistory.id)
        assertThat(savedHistory.memberPassId).isEqualTo(memberPass.id)
        assertThat(savedHistory.reservationId).isEqualTo(reservationId)
        assertThat(savedHistory.type).isEqualTo(PassUsageHistoryType.CONSUME)
        assertThat(savedHistory.reason).isEqualTo(PassUsageHistoryReason.RESERVATION_CONFIRMED)
        assertThat(savedHistory.changedCount).isEqualTo(-1)
        assertThat(savedHistory.remainingCountAfter).isEqualTo(9)
        assertThat(savedHistory.createdAt).isNotNull()
    }

    @Test
    fun `usage result save detects stale member pass version conflict`() {
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val member = saveMember()
        val passProduct = savePassProduct()
        val memberPass = saveMemberPass(member = member, passProduct = passProduct, remainingCount = 10)
        val firstReservationId = saveReservation(member.id, memberPass.id)
        val secondReservationId = saveReservation(member.id, memberPass.id)
        val firstSnapshot = requireNotNull(memberPassRepository.findById(memberPass.id))
        val secondSnapshot = requireNotNull(memberPassRepository.findById(memberPass.id))

        memberPassRepository.saveUsageResult(
            firstSnapshot.consume(reservationId = firstReservationId, now = now),
        )

        assertThatThrownBy {
            memberPassRepository.saveUsageResult(
                secondSnapshot.consume(reservationId = secondReservationId, now = now),
            )
        }.isInstanceOf(ObjectOptimisticLockingFailureException::class.java)

        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(9)
        assertThat(passUsageHistoryJpaRepository.count()).isEqualTo(1)
    }

    @Test
    fun `concurrent usage result save detects member pass version conflict`() {
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val member = saveMember()
        val passProduct = savePassProduct()
        val memberPass = saveMemberPass(member = member, passProduct = passProduct, remainingCount = 10)
        val firstReservationId = saveReservation(member.id, memberPass.id)
        val secondReservationId = saveReservation(member.id, memberPass.id)
        val firstSnapshot = requireNotNull(memberPassRepository.findById(memberPass.id))
        val secondSnapshot = requireNotNull(memberPassRepository.findById(memberPass.id))

        val failures = runConcurrently(
            {
                memberPassRepository.saveUsageResult(
                    firstSnapshot.consume(reservationId = firstReservationId, now = now),
                )
            },
            {
                memberPassRepository.saveUsageResult(
                    secondSnapshot.consume(reservationId = secondReservationId, now = now),
                )
            },
        )

        assertThat(failures.count { it == null }).isEqualTo(1)
        assertThat(failures.filterIsInstance<ObjectOptimisticLockingFailureException>()).hasSize(1)
        assertThat(failures.filterIsInstance<Throwable>()).hasSize(1)
        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(9)
        assertThat(passUsageHistoryJpaRepository.count()).isEqualTo(1)
    }

    @ParameterizedTest
    @EnumSource(value = PassUsageHistoryReason::class, names = ["RESERVATION_CANCELED", "CLASS_SESSION_CANCELED"])
    fun `usage result save persists restore history with member pass state change`(reason: PassUsageHistoryReason) {
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val member = saveMember()
        val passProduct = savePassProduct()
        val memberPass = saveMemberPass(member = member, passProduct = passProduct, remainingCount = 9)
        val reservationId = saveReservation(member.id, memberPass.id)

        memberPassRepository.saveUsageResult(
            memberPass.toDomain().restore(
                reservationId = reservationId,
                reason = reason,
                now = now,
            ),
        )

        val updatedMemberPass = memberPassJpaRepository.findById(memberPass.id).orElseThrow()
        val savedHistory = passUsageHistoryJpaRepository.findAll().single()
        assertThat(updatedMemberPass.remainingCount).isEqualTo(10)
        assertThat(savedHistory.type).isEqualTo(PassUsageHistoryType.RESTORE)
        assertThat(savedHistory.reason).isEqualTo(reason)
        assertThat(savedHistory.changedCount).isEqualTo(1)
        assertThat(savedHistory.remainingCountAfter).isEqualTo(10)
    }

    @Test
    fun `usage history lookup is paged and ordered by created at and id descending`() {
        val createdAt = Instant.parse("2026-05-28T00:00:00Z")
        val member = saveMember()
        val passProduct = savePassProduct()
        val memberPass = saveMemberPass(member = member, passProduct = passProduct, remainingCount = 7)
        val reservationId = saveReservation(member.id, memberPass.id)
        saveUsageHistory(memberPass.id, reservationId, createdAt.minusSeconds(60), remainingCountAfter = 9)
        val second = saveUsageHistory(memberPass.id, reservationId, createdAt, remainingCountAfter = 8)
        val third = saveUsageHistory(memberPass.id, reservationId, createdAt, remainingCountAfter = 7)

        val actual = memberPassRepository.findUsageHistoryPageByMemberPassId(memberPass.id, page = 0, size = 2)

        assertThat(actual.items.map { it.id }).containsExactly(third.id, second.id)
        assertThat(actual.totalElements).isEqualTo(3)
        assertThat(actual.page).isZero()
        assertThat(actual.size).isEqualTo(2)
    }

    @Test
    fun `available pass selection applies expires issued and id ordering`() {
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val member = saveMember()
        val passProduct = savePassProduct()
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = now.minus(10, ChronoUnit.DAYS),
            expiresAt = null,
        )
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = now.minus(20, ChronoUnit.DAYS),
            expiresAt = now.plus(10, ChronoUnit.DAYS),
        )
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = now.minus(5, ChronoUnit.DAYS),
            expiresAt = now.plus(3, ChronoUnit.DAYS),
        )
        val selected = saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = now.minus(30, ChronoUnit.DAYS),
            expiresAt = now.plus(3, ChronoUnit.DAYS),
        )
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = selected.issuedAt,
            expiresAt = selected.expiresAt,
        )

        val actual = memberPassRepository.findAvailablePassForUse(member.id, now)

        assertThat(actual?.id).isEqualTo(selected.id)
    }

    private fun saveMember(): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Hong Gil Dong",
                phone = "010-${System.nanoTime()}",
                email = null,
                status = MemberStatus.ACTIVE,
            ),
        )

    private fun savePassProduct(): PassProductJpaEntity =
        passProductJpaRepository.saveAndFlush(
            PassProductJpaEntity(
                name = "10 Count Pass",
                type = PassProductType.COUNT_PASS,
                totalCount = 10,
                price = null,
                validDays = null,
            ),
        )

    private fun saveMemberPass(
        member: MemberJpaEntity,
        passProduct: PassProductJpaEntity,
        status: MemberPassStatus = MemberPassStatus.ACTIVE,
        remainingCount: Int = passProduct.totalCount,
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant? = issuedAt.plus(90, ChronoUnit.DAYS),
    ): MemberPassJpaEntity =
        memberPassJpaRepository.saveAndFlush(
            MemberPassJpaEntity(
                memberId = member.id,
                passProductId = passProduct.id,
                productNameSnapshot = passProduct.name,
                passTypeSnapshot = passProduct.type,
                totalCount = passProduct.totalCount,
                remainingCount = remainingCount,
                priceSnapshot = passProduct.price,
                validDaysSnapshot = passProduct.validDays,
                status = status,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
            ),
        )

    private fun saveReservation(memberId: Long, memberPassId: Long): Long {
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val startsAt = now.plus(1, ChronoUnit.DAYS)
        val endsAt = startsAt.plus(1, ChronoUnit.HOURS)
        val classSessionId = jdbcTemplate.queryForObject(
            """
            insert into class_sessions (
              title, starts_at, ends_at, capacity, reserved_count, status, created_at, updated_at
            )
            values (?, ?, ?, 10, 1, 'OPEN', ?, ?)
            returning id
            """.trimIndent(),
            Long::class.java,
            "Morning Bouldering",
            Timestamp.from(startsAt),
            Timestamp.from(endsAt),
            Timestamp.from(now),
            Timestamp.from(now),
        ) ?: error("class session id was not returned")

        return jdbcTemplate.queryForObject(
            """
            insert into reservations (
              member_id, class_session_id, member_pass_id, status, reserved_at, created_at, updated_at
            )
            values (?, ?, ?, 'CONFIRMED', ?, ?, ?)
            returning id
            """.trimIndent(),
            Long::class.java,
            memberId,
            classSessionId,
            memberPassId,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
        ) ?: error("reservation id was not returned")
    }

    private fun saveUsageHistory(
        memberPassId: Long,
        reservationId: Long,
        createdAt: Instant,
        remainingCountAfter: Int,
    ): PassUsageHistoryJpaEntity =
        passUsageHistoryJpaRepository.saveAndFlush(
            PassUsageHistoryJpaEntity(
                memberPassId = memberPassId,
                reservationId = reservationId,
                type = PassUsageHistoryType.CONSUME,
                reason = PassUsageHistoryReason.RESERVATION_CONFIRMED,
                changedCount = -1,
                remainingCountAfter = remainingCountAfter,
                createdAt = createdAt,
            ),
        )

    private fun runConcurrently(vararg operations: () -> Unit): List<Throwable?> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(operations.size)

        return try {
            val futures = operations.map { operation ->
                executor.submit(
                    Callable {
                        check(start.await(5, TimeUnit.SECONDS))
                        runCatching { operation() }.exceptionOrNull()
                    },
                )
            }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }
}
