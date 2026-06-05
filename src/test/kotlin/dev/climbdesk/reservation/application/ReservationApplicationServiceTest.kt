package dev.climbdesk.reservation.application

import dev.climbdesk.classsession.domain.ClassSession
import dev.climbdesk.classsession.domain.ClassSessionRepository
import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.event.application.OutboxEventRecorder
import dev.climbdesk.event.domain.OutboxEvent
import dev.climbdesk.member.domain.Member
import dev.climbdesk.member.domain.MemberRepository
import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.pass.domain.MemberPass
import dev.climbdesk.pass.domain.MemberPassRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.MemberPassUsageResult
import dev.climbdesk.pass.domain.MemberPassPage
import dev.climbdesk.pass.domain.PassProductType
import dev.climbdesk.pass.domain.PassUsageHistoryPage
import dev.climbdesk.reservation.domain.Reservation
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent
import dev.climbdesk.reservation.domain.ReservationRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.math.BigDecimal
import java.time.Instant

class ReservationApplicationServiceTest {
    @Test
    fun `member pass optimistic lock conflict maps to member pass version conflict`() {
        val service = ReservationApplicationService(
            memberRepository = StaticMemberRepository(activeMember()),
            classSessionRepository = StaticClassSessionRepository(openClassSession()),
            memberPassRepository = OptimisticLockingMemberPassRepository(availableMemberPass()),
            reservationRepository = StaticReservationRepository(),
            outboxEventRecorder = NoopOutboxEventRecorder(),
        )

        assertThatThrownBy {
            service.reserveClass(CreateReservationCommand(memberId = 1, classSessionId = 2))
        }.isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_PASS_VERSION_CONFLICT)
    }
}

private class StaticMemberRepository(
    private val member: Member?,
) : MemberRepository {
    override fun existsByPhone(phone: String): Boolean = false
    override fun findById(memberId: Long): Member? = member?.takeIf { it.id == memberId }
    override fun findPage(page: Int, size: Int) = error("not used")
    override fun save(member: Member): Member = error("not used")
}

private class StaticClassSessionRepository(
    private val classSession: ClassSession?,
) : ClassSessionRepository {
    override fun findById(classSessionId: Long): ClassSession? = classSession?.takeIf { it.id == classSessionId }
    override fun findByIdForUpdate(classSessionId: Long): ClassSession? = findById(classSessionId)
    override fun findPage(page: Int, size: Int) = error("not used")
    override fun save(classSession: ClassSession): ClassSession = classSession
}

private class OptimisticLockingMemberPassRepository(
    private val memberPass: MemberPass,
) : MemberPassRepository {
    override fun existsById(memberPassId: Long): Boolean = memberPass.id == memberPassId
    override fun findPageByMemberId(memberId: Long, page: Int, size: Int): MemberPassPage = error("not used")
    override fun findUsageHistoryPageByMemberPassId(
        memberPassId: Long,
        page: Int,
        size: Int,
    ): PassUsageHistoryPage = error("not used")

    override fun findAvailablePassForUse(memberId: Long, now: Instant): MemberPass? =
        memberPass.takeIf { it.memberId == memberId }

    override fun save(memberPass: MemberPass): MemberPass = error("not used")

    override fun saveUsageResult(usageResult: MemberPassUsageResult): MemberPassUsageResult {
        throw ObjectOptimisticLockingFailureException(MemberPass::class.java, usageResult.memberPass.id)
    }
}

private class StaticReservationRepository : ReservationRepository {
    override fun existsConfirmedByMemberIdAndClassSessionId(memberId: Long, classSessionId: Long): Boolean = false

    override fun save(reservation: Reservation): Reservation =
        reservation.copy(id = 3, createdAt = Instant.parse("2026-05-01T00:00:00Z"))
}

private class NoopOutboxEventRecorder : OutboxEventRecorder {
    override fun record(event: ReservationConfirmedEvent): OutboxEvent = error("not used")
}

private fun activeMember(): Member =
    Member(
        id = 1,
        name = "Hong Gil Dong",
        phone = "010-1234-5678",
        email = null,
        status = MemberStatus.ACTIVE,
    )

private fun openClassSession(): ClassSession =
    ClassSession(
        id = 2,
        title = "Morning Bouldering",
        startsAt = Instant.parse("2026-05-10T10:00:00Z"),
        endsAt = Instant.parse("2026-05-10T11:00:00Z"),
        capacity = 10,
        reservedCount = 0,
        status = ClassSessionStatus.OPEN,
    )

private fun availableMemberPass(): MemberPass =
    MemberPass(
        id = 4,
        memberId = 1,
        passProductId = 5,
        productNameSnapshot = "10 Count Pass",
        passTypeSnapshot = PassProductType.COUNT_PASS,
        totalCount = 10,
        remainingCount = 10,
        priceSnapshot = BigDecimal("150000"),
        validDaysSnapshot = 90,
        status = MemberPassStatus.ACTIVE,
        issuedAt = Instant.parse("2026-05-01T00:00:00Z"),
        expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
    )
