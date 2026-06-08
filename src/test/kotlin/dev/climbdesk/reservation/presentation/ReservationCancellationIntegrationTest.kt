package dev.climbdesk.reservation.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.TestConcurrencyUtils
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.auth.infrastructure.adapter.Pbkdf2PasswordVerifier
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaEntity
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaEntity
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaRepository
import dev.climbdesk.event.domain.OutboxEventStatus
import dev.climbdesk.event.infrastructure.persistence.OutboxEventJpaRepository
import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassProductType
import dev.climbdesk.pass.domain.PassUsageHistoryReason
import dev.climbdesk.pass.domain.PassUsageHistoryType
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassUsageHistoryJpaRepository
import dev.climbdesk.reservation.domain.ReservationCancelReason
import dev.climbdesk.reservation.domain.ReservationStatus
import dev.climbdesk.reservation.infrastructure.persistence.ReservationJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

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
@AutoConfigureMockMvc
class ReservationCancellationIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
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

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can cancel confirmed reservation`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}-cancel@climbdesk.local", role)
        val member = saveMember()
        val classSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 9)
        val reservationId = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)

        mockMvc.patch("/api/v1/reservations/$reservationId/cancel") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(reservationId) }
            jsonPath("$.memberId") { value(member.id) }
            jsonPath("$.classSessionId") { value(classSession.id) }
            jsonPath("$.memberPassId") { value(memberPass.id) }
            jsonPath("$.status") { value("CANCELED") }
            jsonPath("$.canceledAt") { isNotEmpty() }
            jsonPath("$.cancelReason") { value("USER_REQUESTED") }
            jsonPath("$.classSession.reservedCount") { value(0) }
            jsonPath("$.memberPass.remainingCount") { value(10) }
            jsonPath("$.memberPass.status") { value("ACTIVE") }
        }

        val canceledReservation = reservationJpaRepository.findById(reservationId).orElseThrow()
        val savedClassSession = classSessionJpaRepository.findById(classSession.id).orElseThrow()
        val savedMemberPass = memberPassJpaRepository.findById(memberPass.id).orElseThrow()
        val usageHistory = passUsageHistoryJpaRepository.findAll().single()
        val outboxEvent = outboxEventJpaRepository.findAll().single()

        assertThat(canceledReservation.status).isEqualTo(ReservationStatus.CANCELED)
        assertThat(canceledReservation.canceledAt).isNotNull()
        assertThat(canceledReservation.cancelReason).isEqualTo(ReservationCancelReason.USER_REQUESTED)
        assertThat(savedClassSession.reservedCount).isZero()
        assertThat(savedMemberPass.remainingCount).isEqualTo(10)
        assertThat(usageHistory.memberPassId).isEqualTo(memberPass.id)
        assertThat(usageHistory.reservationId).isEqualTo(reservationId)
        assertThat(usageHistory.type).isEqualTo(PassUsageHistoryType.RESTORE)
        assertThat(usageHistory.reason).isEqualTo(PassUsageHistoryReason.RESERVATION_CANCELED)
        assertThat(usageHistory.changedCount).isEqualTo(1)
        assertThat(usageHistory.remainingCountAfter).isEqualTo(10)
        assertThat(outboxEvent.eventType).isEqualTo("ReservationCanceledEvent")
        assertThat(outboxEvent.aggregateType).isEqualTo("Reservation")
        assertThat(outboxEvent.aggregateId).isEqualTo(reservationId)
        assertThat(outboxEvent.status).isEqualTo(OutboxEventStatus.PENDING)
    }

    @Test
    fun `missing reservation fails with reservation not found`() {
        val token = accessTokenFor("manager-missing-cancel@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.patch("/api/v1/reservations/9223372036854775807/cancel") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESERVATION_NOT_FOUND") }
            jsonPath("$.message") { value("Reservation not found.") }
        }
    }

    @Test
    fun `already canceled reservation fails with reservation already canceled`() {
        val token = accessTokenFor("manager-already-canceled@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember()
        val classSession = saveClassSession()
        val memberPass = saveMemberPass(member)
        val reservationId = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CANCELED)

        mockMvc.patch("/api/v1/reservations/$reservationId/cancel") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("RESERVATION_ALREADY_CANCELED") }
        }

        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
    }

    @Test
    fun `member pass restore not allowed fails without cancellation side effects`() {
        val token = accessTokenFor("manager-restore-not-allowed@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember()
        val classSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 10)
        val reservationId = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)

        mockMvc.patch("/api/v1/reservations/$reservationId/cancel") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("MEMBER_PASS_RESTORE_NOT_ALLOWED") }
        }

        val reservation = reservationJpaRepository.findById(reservationId).orElseThrow()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(reservation.canceledAt).isNull()
        assertThat(reservation.cancelReason).isNull()
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isEqualTo(1)
        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(10)
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
    }

    @Test
    fun `reservation cancellation accepts optional reason request body`() {
        val token = accessTokenFor("manager-cancel-body@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember()
        val classSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 9)
        val reservationId = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)

        mockMvc.patch("/api/v1/reservations/$reservationId/cancel") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"reason":"USER_REQUESTED"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CANCELED") }
            jsonPath("$.cancelReason") { value("USER_REQUESTED") }
        }
    }

    @Test
    fun `cancellation allows re reservation for same member and class session`() {
        val token = accessTokenFor("manager-rereserve@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember()
        val classSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 9)
        val reservationId = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)

        mockMvc.patch("/api/v1/reservations/$reservationId/cancel") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
        }

        mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"memberId":${member.id},"classSessionId":${classSession.id}}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.memberId") { value(member.id) }
            jsonPath("$.classSessionId") { value(classSession.id) }
            jsonPath("$.status") { value("CONFIRMED") }
            jsonPath("$.classSession.reservedCount") { value(1) }
            jsonPath("$.memberPass.remainingCount") { value(9) }
        }

        assertThat(reservationJpaRepository.count()).isEqualTo(2)
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isEqualTo(1)
    }

    @Test
    fun `concurrent cancellation applies side effects once`() {
        val token = accessTokenFor("manager-concurrent-cancel@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember()
        val classSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 9)
        val reservationId = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)
        val statuses = TestConcurrencyUtils.runConcurrently(
            { cancelReservationStatus(token, reservationId) },
            { cancelReservationStatus(token, reservationId) },
        )

        assertThat(statuses).containsExactlyInAnyOrder(200, 409)
        val reservation = reservationJpaRepository.findById(reservationId).orElseThrow()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELED)
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isZero()
        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(10)
        assertThat(passUsageHistoryJpaRepository.count()).isEqualTo(1)
        assertThat(outboxEventJpaRepository.count()).isEqualTo(1)
    }

    private fun cancelReservationStatus(token: String, reservationId: Long): Int =
        mockMvc.patch("/api/v1/reservations/$reservationId/cancel") {
            header("Authorization", "Bearer $token")
        }.andReturn().response.status

    @Test
    fun `reservation cancellation requires jwt authorization`() {
        val member = saveMember()
        val classSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 9)
        val reservationId = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)

        mockMvc.patch("/api/v1/reservations/$reservationId/cancel")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    private fun saveMember(): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Hong Gil Dong",
                phone = "010${memberSequence.getAndIncrement()}".padEnd(11, '0'),
                email = null,
                status = MemberStatus.ACTIVE,
            ),
        )

    private fun saveClassSession(
        reservedCount: Int = 0,
    ): ClassSessionJpaEntity =
        classSessionJpaRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = "Morning Bouldering",
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse("2026-05-10T11:00:00Z"),
                capacity = 10,
                reservedCount = reservedCount,
                status = ClassSessionStatus.OPEN,
            ),
        )

    private fun saveMemberPass(
        member: MemberJpaEntity,
        remainingCount: Int = 10,
    ): MemberPassJpaEntity {
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

    private fun insertReservation(
        memberId: Long,
        classSessionId: Long,
        memberPassId: Long,
        status: ReservationStatus,
    ): Long {
        val now = Instant.parse("2026-05-05T00:00:00Z")
        return jdbcTemplate.queryForObject(
            """
            insert into reservations (
              member_id, class_session_id, member_pass_id, status, reserved_at,
              canceled_at, cancel_reason, created_at, updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning id
            """.trimIndent(),
            Long::class.java,
            memberId,
            classSessionId,
            memberPassId,
            status.name,
            Timestamp.from(now),
            if (status == ReservationStatus.CANCELED) Timestamp.from(now.plus(1, ChronoUnit.HOURS)) else null,
            if (status == ReservationStatus.CANCELED) "USER_REQUESTED" else null,
            Timestamp.from(now),
            Timestamp.from(now),
        ) ?: error("reservation id was not returned")
    }

    private fun accessTokenFor(email: String, role: AdminUserRole): String {
        adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = email,
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = role,
                status = AdminUserStatus.ACTIVE,
            ),
        )

        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password1234"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        return objectMapper.readTree(response)["accessToken"].asText()
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

    private companion object {
        val memberSequence = AtomicInteger(11000000)
    }
}
