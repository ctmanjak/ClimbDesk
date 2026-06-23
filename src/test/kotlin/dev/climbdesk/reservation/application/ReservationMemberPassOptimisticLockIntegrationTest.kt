package dev.climbdesk.reservation.application

import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaEntity
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaRepository
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.event.infrastructure.persistence.OutboxEventJpaRepository
import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
import dev.climbdesk.pass.domain.MemberPass
import dev.climbdesk.pass.domain.MemberPassPage
import dev.climbdesk.pass.domain.MemberPassRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.MemberPassUsageResult
import dev.climbdesk.pass.domain.PassProductType
import dev.climbdesk.pass.domain.PassUsageHistoryPage
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.MemberPassPersistenceAdapter
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassUsageHistoryJpaRepository
import dev.climbdesk.reservation.domain.ReservationStatus
import dev.climbdesk.reservation.infrastructure.persistence.ReservationJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
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
class ReservationMemberPassOptimisticLockIntegrationTest @Autowired constructor(
    private val reservationApplicationService: ReservationApplicationService,
    private val memberJpaRepository: MemberJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
    private val memberPassJpaRepository: MemberPassJpaRepository,
    private val classSessionJpaRepository: ClassSessionJpaRepository,
    private val reservationJpaRepository: ReservationJpaRepository,
    private val passUsageHistoryJpaRepository: PassUsageHistoryJpaRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val optimisticLockCoordinator: MemberPassOptimisticLockCoordinator,
) {
    @BeforeEach
    fun setUp() {
        optimisticLockCoordinator.reset()
        clearData()
    }

    @AfterEach
    fun tearDown() {
        optimisticLockCoordinator.reset()
        clearData()
    }

    @Test
    fun `reservation creation maps real member pass stale version conflict and rolls back failed transaction`() {
        val member = saveMember()
        val firstClassSession = saveClassSession()
        val secondClassSession = saveClassSession()
        val memberPass = saveMemberPass(member, remainingCount = 1)
        optimisticLockCoordinator.coordinateAvailablePassSelection()

        val failures = runConcurrently(
            {
                reservationApplicationService.reserveClass(
                    CreateReservationCommand(memberId = member.id, classSessionId = firstClassSession.id),
                )
            },
            {
                reservationApplicationService.reserveClass(
                    CreateReservationCommand(memberId = member.id, classSessionId = secondClassSession.id),
                )
            },
        )

        assertThat(failures.count { it == null }).isEqualTo(1)
        assertThat(failures.filterIsInstance<ApplicationException>().single().errorCode)
            .isEqualTo(ErrorCode.MEMBER_PASS_VERSION_CONFLICT)

        val reservations = reservationJpaRepository.findAll()
        val savedMemberPass = memberPassJpaRepository.findById(memberPass.id).orElseThrow()
        val savedClassSessions = classSessionJpaRepository.findAllById(
            listOf(firstClassSession.id, secondClassSession.id),
        )

        assertThat(reservations).hasSize(1)
        assertThat(reservations.single().status).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(savedClassSessions.sumOf { it.reservedCount }).isEqualTo(1)
        assertThat(savedClassSessions.map { it.reservedCount }).containsExactlyInAnyOrder(0, 1)
        assertThat(savedMemberPass.remainingCount).isZero()
        assertThat(savedMemberPass.version).isEqualTo(memberPass.version + 1)
        assertThat(passUsageHistoryJpaRepository.count()).isEqualTo(1)
        assertThat(outboxEventJpaRepository.count()).isEqualTo(1)
    }

    @Test
    fun `reservation cancellation maps real member pass stale version conflict and rolls back failed transaction`() {
        val member = saveMember()
        val firstClassSession = saveClassSession(reservedCount = 1)
        val secondClassSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 8)
        val firstReservationId = insertReservation(member.id, firstClassSession.id, memberPass.id)
        val secondReservationId = insertReservation(member.id, secondClassSession.id, memberPass.id)
        optimisticLockCoordinator.coordinateMemberPassLoad(memberPass.id)

        val failures = runConcurrently(
            { reservationApplicationService.cancelReservation(firstReservationId) },
            { reservationApplicationService.cancelReservation(secondReservationId) },
        )

        assertThat(failures.count { it == null }).isEqualTo(1)
        assertThat(failures.filterIsInstance<ApplicationException>().single().errorCode)
            .isEqualTo(ErrorCode.MEMBER_PASS_VERSION_CONFLICT)

        val reservations = reservationJpaRepository.findAll()
        val savedMemberPass = memberPassJpaRepository.findById(memberPass.id).orElseThrow()
        val savedClassSessions = classSessionJpaRepository.findAllById(
            listOf(firstClassSession.id, secondClassSession.id),
        )

        assertThat(reservations.map { it.status }).containsExactlyInAnyOrder(
            ReservationStatus.CONFIRMED,
            ReservationStatus.CANCELED,
        )
        assertThat(reservations.single { it.status == ReservationStatus.CONFIRMED }.canceledAt).isNull()
        assertThat(savedClassSessions.sumOf { it.reservedCount }).isEqualTo(1)
        assertThat(savedClassSessions.map { it.reservedCount }).containsExactlyInAnyOrder(0, 1)
        assertThat(savedMemberPass.remainingCount).isEqualTo(9)
        assertThat(savedMemberPass.version).isEqualTo(memberPass.version + 1)
        assertThat(passUsageHistoryJpaRepository.count()).isEqualTo(1)
        assertThat(outboxEventJpaRepository.count()).isEqualTo(1)
    }

    private fun saveMember(): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Hong Gil Dong",
                phone = "010${System.nanoTime()}".take(11),
                email = null,
                status = MemberStatus.ACTIVE,
            ),
        )

    private fun saveClassSession(reservedCount: Int = 0): ClassSessionJpaEntity =
        classSessionJpaRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = "Morning Bouldering",
                startsAt = Instant.parse("2026-05-10T10:00:00Z").plus(System.nanoTime() % 1000, ChronoUnit.SECONDS),
                endsAt = Instant.parse("2026-05-10T11:00:00Z").plus(System.nanoTime() % 1000, ChronoUnit.SECONDS),
                capacity = 10,
                reservedCount = reservedCount,
                status = ClassSessionStatus.OPEN,
            ),
        )

    private fun saveMemberPass(member: MemberJpaEntity, remainingCount: Int): MemberPassJpaEntity {
        val passProduct = passProductJpaRepository.saveAndFlush(
            PassProductJpaEntity(
                name = "10 Count Pass",
                type = PassProductType.COUNT_PASS,
                totalCount = 10,
                price = null,
                validDays = 90,
            ),
        )

        return memberPassJpaRepository.saveAndFlush(
            MemberPassJpaEntity(
                memberId = member.id,
                passProductId = passProduct.id,
                productNameSnapshot = passProduct.name,
                passTypeSnapshot = passProduct.type,
                totalCount = passProduct.totalCount,
                remainingCount = remainingCount,
                priceSnapshot = passProduct.price,
                validDaysSnapshot = passProduct.validDays,
                status = MemberPassStatus.ACTIVE,
                issuedAt = Instant.parse("2026-05-01T00:00:00Z"),
                expiresAt = Instant.parse("2026-05-01T00:00:00Z").plus(90, ChronoUnit.DAYS),
            ),
        )
    }

    private fun insertReservation(memberId: Long, classSessionId: Long, memberPassId: Long): Long {
        val now = Instant.parse("2026-05-05T00:00:00Z")
        return jdbcTemplate.queryForObject(
            """
            insert into reservations (
              member_id, class_session_id, member_pass_id, status, reserved_at,
              canceled_at, cancel_reason, created_at, updated_at
            )
            values (?, ?, ?, 'CONFIRMED', ?, null, null, ?, ?)
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

    private fun clearData() {
        outboxEventJpaRepository.deleteAll()
        passUsageHistoryJpaRepository.deleteAll()
        reservationJpaRepository.deleteAll()
        classSessionJpaRepository.deleteAll()
        memberPassJpaRepository.deleteAll()
        passProductJpaRepository.deleteAll()
        memberJpaRepository.deleteAll()
    }

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

    @TestConfiguration
    class CoordinatedMemberPassConfiguration {
        @Bean
        fun optimisticLockCoordinator(): MemberPassOptimisticLockCoordinator =
            MemberPassOptimisticLockCoordinator()

        @Bean
        @Primary
        fun coordinatedMemberPassRepository(
            delegate: MemberPassPersistenceAdapter,
            optimisticLockCoordinator: MemberPassOptimisticLockCoordinator,
        ): MemberPassRepository =
            object : MemberPassRepository {
                override fun existsById(memberPassId: Long): Boolean =
                    delegate.existsById(memberPassId)

                override fun findById(memberPassId: Long): MemberPass? =
                    delegate.findById(memberPassId)
                        ?.also { optimisticLockCoordinator.afterMemberPassLoad(memberPassId) }

                override fun findPageByMemberId(memberId: Long, page: Int, size: Int): MemberPassPage =
                    delegate.findPageByMemberId(memberId, page, size)

                override fun findUsageHistoryPageByMemberPassId(
                    memberPassId: Long,
                    page: Int,
                    size: Int,
                ): PassUsageHistoryPage =
                    delegate.findUsageHistoryPageByMemberPassId(memberPassId, page, size)

                override fun findAvailablePassForUse(memberId: Long, now: Instant): MemberPass? =
                    delegate.findAvailablePassForUse(memberId, now)
                        ?.also { optimisticLockCoordinator.afterAvailablePassSelection() }

                override fun save(memberPass: MemberPass): MemberPass =
                    delegate.save(memberPass)

                override fun saveUsageResult(usageResult: MemberPassUsageResult): MemberPassUsageResult =
                    delegate.saveUsageResult(usageResult)
            }
    }
}

class MemberPassOptimisticLockCoordinator {
    @Volatile
    private var availablePassSelectionBarrier: CountDownLatch? = null

    @Volatile
    private var memberPassLoadBarrier: MemberPassLoadBarrier? = null

    fun coordinateAvailablePassSelection() {
        availablePassSelectionBarrier = CountDownLatch(2)
    }

    fun coordinateMemberPassLoad(memberPassId: Long) {
        memberPassLoadBarrier = MemberPassLoadBarrier(memberPassId = memberPassId, latch = CountDownLatch(2))
    }

    fun afterAvailablePassSelection() {
        await(availablePassSelectionBarrier)
    }

    fun afterMemberPassLoad(memberPassId: Long) {
        val barrier = memberPassLoadBarrier?.takeIf { it.memberPassId == memberPassId }?.latch
        await(barrier)
    }

    fun reset() {
        release(availablePassSelectionBarrier)
        release(memberPassLoadBarrier?.latch)
        availablePassSelectionBarrier = null
        memberPassLoadBarrier = null
    }

    private fun release(latch: CountDownLatch?) {
        latch?.countDown()
        latch?.countDown()
    }

    private fun await(latch: CountDownLatch?) {
        if (latch == null) {
            return
        }
        latch.countDown()
        check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for member pass optimistic lock coordination." }
    }

    private data class MemberPassLoadBarrier(
        val memberPassId: Long,
        val latch: CountDownLatch,
    )
}
