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
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.testcontainers.junit.jupiter.Testcontainers
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
class ReservationCreationMemberPassFailureIntegrationTest @Autowired constructor(
    private val reservationApplicationService: ReservationApplicationService,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
    private val memberPassJpaRepository: MemberPassJpaRepository,
    private val classSessionJpaRepository: ClassSessionJpaRepository,
    private val reservationJpaRepository: ReservationJpaRepository,
    private val passUsageHistoryJpaRepository: PassUsageHistoryJpaRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
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
    fun `member pass version conflict rolls back reservation creation transaction`() {
        val member = saveMember()
        val classSession = saveClassSession()
        val memberPass = saveMemberPass(member)

        assertThatThrownBy {
            reservationApplicationService.reserveClass(
                CreateReservationCommand(
                    memberId = member.id,
                    classSessionId = classSession.id,
                ),
            )
        }.isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_PASS_VERSION_CONFLICT)

        assertThat(reservationJpaRepository.count()).isZero()
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isZero()
        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(10)
    }

    private fun saveMember(): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Hong Gil Dong",
                phone = "01092000000",
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
                reservedCount = 0,
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
                totalCount = 10,
                remainingCount = 10,
                priceSnapshot = passProduct.price,
                validDaysSnapshot = passProduct.validDays,
                status = MemberPassStatus.ACTIVE,
                issuedAt = Instant.parse("2026-05-01T00:00:00Z"),
                expiresAt = Instant.parse("2026-05-01T00:00:00Z").plus(90, ChronoUnit.DAYS),
            ),
        )
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
