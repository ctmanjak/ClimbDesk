package dev.climbdesk.reservation.application

import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

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
class ReservationCancellationMemberPassFailureIntegrationTest @Autowired constructor(
    private val reservationApplicationService: ReservationApplicationService,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
    private val memberPassJpaRepository: MemberPassJpaRepository,
    private val classSessionJpaRepository: ClassSessionJpaRepository,
    private val reservationJpaRepository: ReservationJpaRepository,
    private val passUsageHistoryJpaRepository: PassUsageHistoryJpaRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
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
    fun `member pass version conflict rolls back reservation cancellation transaction`() {
        val member = saveMember()
        val classSession = saveClassSession()
        val memberPass = saveMemberPass(member)
        val reservationId = insertReservation(member.id, classSession.id, memberPass.id)

        assertThatThrownBy {
            reservationApplicationService.cancelReservation(reservationId)
        }.isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_PASS_VERSION_CONFLICT)

        val reservation = reservationJpaRepository.findById(reservationId).orElseThrow()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(reservation.canceledAt).isNull()
        assertThat(reservation.cancelReason).isNull()
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isEqualTo(1)
        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(9)
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
    }

    private fun saveMember(): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Hong Gil Dong",
                phone = "01093000000",
                email = null,
                status = MemberStatus.ACTIVE,
            ),
        )

    private fun saveClassSession(): ClassSessionJpaEntity =
        classSessionJpaRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = "Morning Bouldering",
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse("2026-05-10T11:00:00Z"),
                capacity = 10,
                reservedCount = 1,
                status = ClassSessionStatus.OPEN,
            ),
        )

    private fun saveMemberPass(member: MemberJpaEntity): MemberPassJpaEntity {
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
                remainingCount = 9,
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
        adminUserJpaRepository.deleteAll()
    }

    @TestConfiguration
    class FailingMemberPassConfiguration {
        @Bean
        @Primary
        fun failingMemberPassRepository(delegate: MemberPassPersistenceAdapter): MemberPassRepository =
            object : MemberPassRepository {
                override fun existsById(memberPassId: Long): Boolean =
                    delegate.existsById(memberPassId)

                override fun findById(memberPassId: Long): MemberPass? =
                    delegate.findById(memberPassId)

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

                override fun save(memberPass: MemberPass): MemberPass =
                    delegate.save(memberPass)

                override fun saveUsageResult(usageResult: MemberPassUsageResult): MemberPassUsageResult {
                    throw ObjectOptimisticLockingFailureException(MemberPass::class.java, usageResult.memberPass.id)
                }
            }
    }
}
