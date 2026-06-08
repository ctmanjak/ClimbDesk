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
import dev.climbdesk.reservation.domain.ReservationCanceledEvent
import dev.climbdesk.reservation.domain.ReservationClassSessionSummary
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent
import dev.climbdesk.reservation.domain.ReservationFilters
import dev.climbdesk.reservation.domain.ReservationMemberPassSummary
import dev.climbdesk.reservation.domain.ReservationRepository
import dev.climbdesk.reservation.domain.ReservationSummary
import dev.climbdesk.reservation.domain.ReservationSummaryPage
import dev.climbdesk.reservation.domain.ReservationStatus
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.assertThat
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

    @Test
    fun `member pass optimistic lock conflict during cancellation maps to member pass version conflict`() {
        val service = ReservationApplicationService(
            memberRepository = StaticMemberRepository(activeMember()),
            classSessionRepository = StaticClassSessionRepository(openClassSession(reservedCount = 1)),
            memberPassRepository = OptimisticLockingMemberPassRepository(availableMemberPass(remainingCount = 9)),
            reservationRepository = StaticReservationRepository(domainReservation = confirmedReservation()),
            outboxEventRecorder = NoopOutboxEventRecorder(),
        )

        assertThatThrownBy { service.cancelReservation(10) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEMBER_PASS_VERSION_CONFLICT)
    }

    @Test
    fun `get reservation maps missing reservation to reservation not found`() {
        val service = ReservationApplicationService(
            memberRepository = StaticMemberRepository(activeMember()),
            classSessionRepository = StaticClassSessionRepository(openClassSession()),
            memberPassRepository = StaticMemberPassRepository(),
            reservationRepository = StaticReservationRepository(),
            outboxEventRecorder = NoopOutboxEventRecorder(),
        )

        assertThatThrownBy { service.getReservation(999) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND)
    }

    @Test
    fun `list reservations applies page constraints and filters`() {
        val repository = StaticReservationRepository(listOf(reservationSummary()))
        val service = ReservationApplicationService(
            memberRepository = StaticMemberRepository(activeMember()),
            classSessionRepository = StaticClassSessionRepository(openClassSession()),
            memberPassRepository = StaticMemberPassRepository(),
            reservationRepository = repository,
            outboxEventRecorder = NoopOutboxEventRecorder(),
        )

        val result = service.listReservations(
            page = 0,
            size = 20,
            memberId = 1,
            classSessionId = 2,
            status = ReservationStatus.CONFIRMED,
        )

        assertThat(result.items).hasSize(1)
        assertThat(result.totalElements).isEqualTo(1)
        assertThat(repository.lastFilters).isEqualTo(
            ReservationFilters(
                memberId = 1,
                classSessionId = 2,
                status = ReservationStatus.CONFIRMED,
            ),
        )
    }

    @Test
    fun `list reservations rejects invalid paging`() {
        val service = ReservationApplicationService(
            memberRepository = StaticMemberRepository(activeMember()),
            classSessionRepository = StaticClassSessionRepository(openClassSession()),
            memberPassRepository = StaticMemberPassRepository(),
            reservationRepository = StaticReservationRepository(),
            outboxEventRecorder = NoopOutboxEventRecorder(),
        )

        assertThatThrownBy { service.listReservations(-1, 20, null, null, null) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)

        assertThatThrownBy { service.listReservations(0, 101, null, null, null) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
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
    override fun findById(memberPassId: Long): MemberPass? = memberPass.takeIf { it.id == memberPassId }
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

private class StaticMemberPassRepository : MemberPassRepository {
    override fun existsById(memberPassId: Long): Boolean = false
    override fun findById(memberPassId: Long): MemberPass? = null
    override fun findPageByMemberId(memberId: Long, page: Int, size: Int): MemberPassPage = error("not used")
    override fun findUsageHistoryPageByMemberPassId(
        memberPassId: Long,
        page: Int,
        size: Int,
    ): PassUsageHistoryPage = error("not used")

    override fun findAvailablePassForUse(memberId: Long, now: Instant): MemberPass? = error("not used")
    override fun save(memberPass: MemberPass): MemberPass = error("not used")
    override fun saveUsageResult(usageResult: MemberPassUsageResult): MemberPassUsageResult = error("not used")
}

private class StaticReservationRepository(
    private val summaries: List<ReservationSummary> = emptyList(),
    private val domainReservation: Reservation? = null,
) : ReservationRepository {
    var lastFilters: ReservationFilters? = null
        private set

    override fun existsConfirmedByMemberIdAndClassSessionId(memberId: Long, classSessionId: Long): Boolean = false

    override fun findById(reservationId: Long): ReservationSummary? =
        summaries.firstOrNull { it.id == reservationId }

    override fun findDomainById(reservationId: Long): Reservation? =
        domainReservation?.takeIf { it.id == reservationId }

    override fun findPage(filters: ReservationFilters, page: Int, size: Int): ReservationSummaryPage {
        lastFilters = filters
        return ReservationSummaryPage(
            items = summaries,
            page = page,
            size = size,
            totalElements = summaries.size.toLong(),
        )
    }

    override fun save(reservation: Reservation): Reservation =
        reservation.copy(id = 3, createdAt = Instant.parse("2026-05-01T00:00:00Z"))
}

private class NoopOutboxEventRecorder : OutboxEventRecorder {
    override fun record(event: ReservationConfirmedEvent): OutboxEvent = error("not used")
    override fun record(event: ReservationCanceledEvent): OutboxEvent = error("not used")
}

private fun activeMember(): Member =
    Member(
        id = 1,
        name = "Hong Gil Dong",
        phone = "010-1234-5678",
        email = null,
        status = MemberStatus.ACTIVE,
    )

private fun openClassSession(reservedCount: Int = 0): ClassSession =
    ClassSession(
        id = 2,
        title = "Morning Bouldering",
        startsAt = Instant.parse("2026-05-10T10:00:00Z"),
        endsAt = Instant.parse("2026-05-10T11:00:00Z"),
        capacity = 10,
        reservedCount = reservedCount,
        status = ClassSessionStatus.OPEN,
    )

private fun availableMemberPass(remainingCount: Int = 10): MemberPass =
    MemberPass(
        id = 4,
        memberId = 1,
        passProductId = 5,
        productNameSnapshot = "10 Count Pass",
        passTypeSnapshot = PassProductType.COUNT_PASS,
        totalCount = 10,
        remainingCount = remainingCount,
        priceSnapshot = BigDecimal("150000"),
        validDaysSnapshot = 90,
        status = MemberPassStatus.ACTIVE,
        issuedAt = Instant.parse("2026-05-01T00:00:00Z"),
        expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
    )

private fun confirmedReservation(): Reservation =
    Reservation(
        id = 10,
        memberId = 1,
        classSessionId = 2,
        memberPassId = 4,
        status = ReservationStatus.CONFIRMED,
        reservedAt = Instant.parse("2026-05-01T00:00:00Z"),
    )

private fun reservationSummary(): ReservationSummary =
    ReservationSummary(
        id = 10,
        memberId = 1,
        classSessionId = 2,
        memberPassId = 4,
        status = ReservationStatus.CONFIRMED,
        reservedAt = Instant.parse("2026-05-01T00:00:00Z"),
        canceledAt = null,
        cancelReason = null,
        classSession = ReservationClassSessionSummary(
            id = 2,
            capacity = 10,
            reservedCount = 1,
            status = ClassSessionStatus.OPEN,
        ),
        memberPass = ReservationMemberPassSummary(
            id = 4,
            remainingCount = 9,
            status = MemberPassStatus.ACTIVE,
        ),
    )
