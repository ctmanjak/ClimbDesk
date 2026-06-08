package dev.climbdesk.classsession.presentation

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
import org.junit.jupiter.params.provider.ValueSource
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
class ClassSessionCancellationIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val classSessionJpaRepository: ClassSessionJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
    private val memberPassJpaRepository: MemberPassJpaRepository,
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
    fun `manager and staff can cancel open class session`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val classSession = saveClassSession()

        mockMvc.patch("/api/v1/class-sessions/${classSession.id}/cancel") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"reason":"Operational issue"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(classSession.id) }
            jsonPath("$.reservedCount") { value(0) }
            jsonPath("$.status") { value("CANCELED") }
            jsonPath("$.canceledAt") { isNotEmpty() }
            jsonPath("$.affectedReservationCount") { value(0) }
        }

        val canceled = classSessionJpaRepository.findById(classSession.id).orElseThrow()
        assertThat(canceled.status).isEqualTo(ClassSessionStatus.CANCELED)
        assertThat(canceled.reservedCount).isZero()
        assertThat(canceled.canceledAt).isNotNull()
        assertThat(canceled.cancelReason).isEqualTo("Operational issue")
        assertThat(canceled.affectedReservationCount).isZero()
    }

    @Test
    fun `class session cancellation cancels confirmed reservations and restores passes atomically`() {
        val managerToken = accessTokenFor("manager-side-effects@climbdesk.local", AdminUserRole.MANAGER)
        val firstMember = saveMember()
        val secondMember = saveMember()
        val classSession = saveClassSession(reservedCount = 2)
        val firstPass = saveMemberPass(firstMember, remainingCount = 9)
        val secondPass = saveMemberPass(secondMember, remainingCount = 9)
        val firstReservationId = insertReservation(firstMember.id, classSession.id, firstPass.id)
        val secondReservationId = insertReservation(secondMember.id, classSession.id, secondPass.id)

        cancelClassSession(classSession.id, managerToken).andExpect {
            status { isOk() }
            jsonPath("$.reservedCount") { value(0) }
            jsonPath("$.status") { value("CANCELED") }
            jsonPath("$.affectedReservationCount") { value(2) }
        }

        val reservations = reservationJpaRepository.findAll().sortedBy { it.id }
        assertThat(reservations).extracting<ReservationStatus> { it.status }
            .containsExactly(ReservationStatus.CANCELED, ReservationStatus.CANCELED)
        assertThat(reservations).extracting<ReservationCancelReason> { it.cancelReason }
            .containsExactly(
                ReservationCancelReason.CLASS_SESSION_CANCELED,
                ReservationCancelReason.CLASS_SESSION_CANCELED,
            )
        assertThat(memberPassJpaRepository.findById(firstPass.id).orElseThrow().remainingCount).isEqualTo(10)
        assertThat(memberPassJpaRepository.findById(secondPass.id).orElseThrow().remainingCount).isEqualTo(10)

        val histories = passUsageHistoryJpaRepository.findAll().sortedBy { it.reservationId }
        assertThat(histories).hasSize(2)
        assertThat(histories).extracting<Long> { it.reservationId }
            .containsExactly(firstReservationId, secondReservationId)
        assertThat(histories).extracting<PassUsageHistoryType> { it.type }
            .containsExactly(PassUsageHistoryType.RESTORE, PassUsageHistoryType.RESTORE)
        assertThat(histories).extracting<PassUsageHistoryReason> { it.reason }
            .containsExactly(
                PassUsageHistoryReason.CLASS_SESSION_CANCELED,
                PassUsageHistoryReason.CLASS_SESSION_CANCELED,
            )

        val outboxEvent = outboxEventJpaRepository.findAll().single()
        val payload = objectMapper.readTree(outboxEvent.payload)
        assertThat(outboxEvent.eventType).isEqualTo("ClassSessionCanceledEvent")
        assertThat(outboxEvent.aggregateType).isEqualTo("ClassSession")
        assertThat(outboxEvent.aggregateId).isEqualTo(classSession.id)
        assertThat(payload["classSessionId"].longValue()).isEqualTo(classSession.id)
        assertThat(payload["affectedReservationCount"].intValue()).isEqualTo(2)
    }

    @Test
    fun `class session cancellation has no reservation side effects when no confirmed reservations exist`() {
        val managerToken = accessTokenFor("manager-no-reservations@climbdesk.local", AdminUserRole.MANAGER)
        val classSession = saveClassSession()

        cancelClassSession(classSession.id, managerToken).andExpect {
            status { isOk() }
            jsonPath("$.reservedCount") { value(0) }
            jsonPath("$.status") { value("CANCELED") }
            jsonPath("$.affectedReservationCount") { value(0) }
        }

        assertThat(reservationJpaRepository.count()).isZero()
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.findAll()).hasSize(1)
    }

    @Test
    fun `member pass restore failure rolls back class session cancellation`() {
        val managerToken = accessTokenFor("manager-rollback@climbdesk.local", AdminUserRole.MANAGER)
        val firstMember = saveMember()
        val secondMember = saveMember()
        val classSession = saveClassSession(reservedCount = 2)
        val firstPass = saveMemberPass(firstMember, remainingCount = 9)
        val secondPass = saveMemberPass(secondMember, remainingCount = 10)
        val firstReservationId = insertReservation(firstMember.id, classSession.id, firstPass.id)
        val secondReservationId = insertReservation(secondMember.id, classSession.id, secondPass.id)

        cancelClassSession(classSession.id, managerToken).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("MEMBER_PASS_RESTORE_NOT_ALLOWED") }
        }

        val firstReservation = reservationJpaRepository.findById(firstReservationId).orElseThrow()
        val secondReservation = reservationJpaRepository.findById(secondReservationId).orElseThrow()
        val savedClassSession = classSessionJpaRepository.findById(classSession.id).orElseThrow()
        assertThat(firstReservation.status).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(firstReservation.canceledAt).isNull()
        assertThat(firstReservation.cancelReason).isNull()
        assertThat(secondReservation.status).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(secondReservation.canceledAt).isNull()
        assertThat(secondReservation.cancelReason).isNull()
        assertThat(savedClassSession.status).isEqualTo(ClassSessionStatus.OPEN)
        assertThat(savedClassSession.reservedCount).isEqualTo(2)
        assertThat(memberPassJpaRepository.findById(firstPass.id).orElseThrow().remainingCount).isEqualTo(9)
        assertThat(memberPassJpaRepository.findById(secondPass.id).orElseThrow().remainingCount).isEqualTo(10)
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
    }

    @Test
    fun `concurrent class session cancellation and reservation creation remain consistent`() {
        val token = accessTokenFor("manager-cancel-create-race@climbdesk.local", AdminUserRole.MANAGER)
        val existingMember = saveMember()
        val racingMember = saveMember()
        val classSession = saveClassSession(capacity = 2, reservedCount = 1)
        val existingPass = saveMemberPass(existingMember, remainingCount = 9)
        val racingPass = saveMemberPass(racingMember, remainingCount = 10)
        insertReservation(existingMember.id, classSession.id, existingPass.id)

        val statuses = TestConcurrencyUtils.runConcurrently(
            { cancelClassSessionStatus(classSession.id, token) },
            { postReservationStatus(token, racingMember.id, classSession.id) },
        )
        val cancellationStatus = statuses[0]
        val reservationStatus = statuses[1]

        assertThat(cancellationStatus).isEqualTo(200)
        assertThat(reservationStatus).isIn(201, 409)

        val savedClassSession = classSessionJpaRepository.findById(classSession.id).orElseThrow()
        assertThat(savedClassSession.status).isEqualTo(ClassSessionStatus.CANCELED)
        assertThat(savedClassSession.reservedCount).isZero()
        assertThat(savedClassSession.affectedReservationCount).isEqualTo(if (reservationStatus == 201) 2 else 1)

        val reservations = reservationJpaRepository.findAll()
        assertThat(reservations).hasSize(if (reservationStatus == 201) 2 else 1)
        assertThat(reservations).allSatisfy { reservation ->
            assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELED)
            assertThat(reservation.cancelReason).isEqualTo(ReservationCancelReason.CLASS_SESSION_CANCELED)
        }
        assertThat(
            reservationJpaRepository.existsByMemberIdAndClassSessionIdAndStatus(
                racingMember.id,
                classSession.id,
                ReservationStatus.CONFIRMED,
            ),
        ).isFalse()
        assertThat(memberPassJpaRepository.findById(existingPass.id).orElseThrow().remainingCount).isEqualTo(10)
        assertThat(memberPassJpaRepository.findById(racingPass.id).orElseThrow().remainingCount).isEqualTo(10)
        assertThat(passUsageHistoryJpaRepository.count()).isEqualTo(if (reservationStatus == 201) 3 else 1)
        assertThat(outboxEventJpaRepository.findAll().map { it.eventType })
            .containsExactlyInAnyOrderElementsOf(
                if (reservationStatus == 201) {
                    listOf("ReservationConfirmedEvent", "ClassSessionCanceledEvent")
                } else {
                    listOf("ClassSessionCanceledEvent")
                },
            )
    }

    @Test
    fun `canceled class session cannot be canceled again`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val classSession = saveClassSession()

        cancelClassSession(classSession.id, managerToken).andExpect {
            status { isOk() }
        }

        cancelClassSession(classSession.id, managerToken).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CLASS_SESSION_ALREADY_CANCELED") }
        }
    }

    @Test
    fun `missing class session returns class session not found`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        cancelClassSession(Long.MAX_VALUE, managerToken).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("CLASS_SESSION_NOT_FOUND") }
        }
    }

    @Test
    fun `class session cancellation requires jwt authorization`() {
        val classSession = saveClassSession()

        mockMvc.patch("/api/v1/class-sessions/${classSession.id}/cancel") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"Operational issue"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    fun `class session cancellation rejects blank reason`(reason: String) {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val classSession = saveClassSession()

        mockMvc.patch("/api/v1/class-sessions/${classSession.id}/cancel") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"reason":"$reason"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `class session cancellation rejects reason over maximum length`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val classSession = saveClassSession()

        mockMvc.patch("/api/v1/class-sessions/${classSession.id}/cancel") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"reason":"${"a".repeat(501)}"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    private fun cancelClassSession(classSessionId: Long, token: String) =
        mockMvc.patch("/api/v1/class-sessions/$classSessionId/cancel") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"reason":"Operational issue"}"""
        }

    private fun cancelClassSessionStatus(classSessionId: Long, token: String): Int =
        cancelClassSession(classSessionId, token)
            .andReturn()
            .response
            .status

    private fun postReservationStatus(token: String, memberId: Long, classSessionId: Long): Int =
        mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"memberId":$memberId,"classSessionId":$classSessionId}"""
        }.andReturn().response.status

    private fun saveClassSession(
        capacity: Int = 12,
        reservedCount: Int = 0,
    ): ClassSessionJpaEntity =
        classSessionJpaRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = "Beginner Bouldering",
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse("2026-05-10T11:00:00Z"),
                capacity = capacity,
                reservedCount = reservedCount,
                status = ClassSessionStatus.OPEN,
            ),
        )

    private fun saveMember(): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Hong Gil Dong",
                phone = "010${memberSequence.getAndIncrement()}".padEnd(11, '0'),
                email = null,
                status = MemberStatus.ACTIVE,
            ),
        )

    private fun saveMemberPass(
        member: MemberJpaEntity,
        remainingCount: Int,
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
                status = if (remainingCount == 0) MemberPassStatus.EXHAUSTED else MemberPassStatus.ACTIVE,
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
        val memberSequence = AtomicInteger(10000000)
    }
}
