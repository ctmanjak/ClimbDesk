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
            header("X-Trace-Id", RESERVATION_CANCEL_TRACE_ID)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESERVATION_NOT_FOUND") }
            jsonPath("$.message") { value("Reservation not found.") }
            expectReservationCancelErrorShape(
                status = 404,
                code = "RESERVATION_NOT_FOUND",
                message = "Reservation not found.",
                reservationId = 9223372036854775807,
            )
        }

        assertThat(reservationJpaRepository.count()).isZero()
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
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
            header("X-Trace-Id", RESERVATION_CANCEL_TRACE_ID)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("RESERVATION_ALREADY_CANCELED") }
            expectReservationCancelErrorShape(
                status = 409,
                code = "RESERVATION_ALREADY_CANCELED",
                message = "Reservation is already canceled.",
                reservationId = reservationId,
            )
        }

        val reservation = reservationJpaRepository.findById(reservationId).orElseThrow()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELED)
        assertThat(reservation.canceledAt).isNotNull()
        assertThat(reservation.cancelReason).isEqualTo(ReservationCancelReason.USER_REQUESTED)
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isZero()
        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(10)
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
        val results = TestConcurrencyUtils.runConcurrently(
            { cancelReservationResult(token, reservationId) },
            { cancelReservationResult(token, reservationId) },
        )

        assertThat(results.map { it.status }).containsExactlyInAnyOrder(200, 409)
        assertThat(results.single { it.status == 409 }.code).isEqualTo("RESERVATION_ALREADY_CANCELED")
        val reservation = reservationJpaRepository.findById(reservationId).orElseThrow()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELED)
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isZero()
        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(10)
        assertThat(passUsageHistoryJpaRepository.count()).isEqualTo(1)
        assertThat(outboxEventJpaRepository.count()).isEqualTo(1)
    }

    @Test
    fun `concurrent reservation cancellation and creation remain consistent`() {
        val token = accessTokenFor("manager-cancel-create@climbdesk.local", AdminUserRole.MANAGER)
        val existingMember = saveMember()
        val racingMember = saveMember()
        val classSession = saveClassSession(capacity = 1, reservedCount = 1)
        val existingPass = saveMemberPass(existingMember, remainingCount = 9)
        val racingPass = saveMemberPass(racingMember, remainingCount = 10)
        val existingReservationId = insertReservation(
            existingMember.id,
            classSession.id,
            existingPass.id,
            ReservationStatus.CONFIRMED,
        )

        val results = TestConcurrencyUtils.runConcurrently(
            { cancelReservationResult(token, existingReservationId) },
            { postReservationResult(token, racingMember.id, classSession.id) },
        )
        val cancellationResult = results[0]
        val creationResult = results[1]
        val creationCommitted = creationResult.status == 201

        assertThat(cancellationResult.status).isEqualTo(200)
        assertThat(creationResult.status).isIn(201, 409)
        if (creationResult.status == 409) {
            assertThat(creationResult.code).isEqualTo("CLASS_SESSION_FULL")
        }

        val savedClassSession = classSessionJpaRepository.findById(classSession.id).orElseThrow()
        val reservations = reservationJpaRepository.findAll()
        val confirmedReservations = reservations.filter { it.status == ReservationStatus.CONFIRMED }
        val canceledReservation = reservations.single { it.id == existingReservationId }

        assertThat(savedClassSession.reservedCount).isLessThanOrEqualTo(savedClassSession.capacity)
        assertThat(savedClassSession.reservedCount).isEqualTo(confirmedReservations.size)
        assertThat(canceledReservation.status).isEqualTo(ReservationStatus.CANCELED)
        assertThat(canceledReservation.canceledAt).isNotNull()
        assertThat(canceledReservation.cancelReason).isEqualTo(ReservationCancelReason.USER_REQUESTED)
        assertThat(
            reservationJpaRepository.existsByMemberIdAndClassSessionIdAndStatus(
                existingMember.id,
                classSession.id,
                ReservationStatus.CONFIRMED,
            ),
        ).isFalse()
        if (creationCommitted) {
            assertThat(reservations).hasSize(2)
            confirmedReservations.single().also { reservation ->
                assertThat(reservation.memberId).isEqualTo(racingMember.id)
                assertThat(reservation.classSessionId).isEqualTo(classSession.id)
                assertThat(reservation.memberPassId).isEqualTo(racingPass.id)
            }
        } else {
            assertThat(reservations).hasSize(1)
            assertThat(confirmedReservations).isEmpty()
        }

        memberPassJpaRepository.findById(existingPass.id).orElseThrow().also { savedExistingPass ->
            assertThat(savedExistingPass.remainingCount).isEqualTo(10)
            assertThat(savedExistingPass.version).isEqualTo(existingPass.version + 1)
        }
        memberPassJpaRepository.findById(racingPass.id).orElseThrow().also { savedRacingPass ->
            assertThat(savedRacingPass.remainingCount).isEqualTo(if (creationCommitted) 9 else 10)
            assertThat(savedRacingPass.version).isEqualTo(racingPass.version + if (creationCommitted) 1 else 0)
        }

        val histories = passUsageHistoryJpaRepository.findAll()
        assertThat(histories).hasSize(1 + if (creationCommitted) 1 else 0)
        assertThat(histories.count { it.type == PassUsageHistoryType.RESTORE }).isEqualTo(1)
        assertThat(histories.count { it.type == PassUsageHistoryType.CONSUME }).isEqualTo(if (creationCommitted) 1 else 0)
        histories.single { it.type == PassUsageHistoryType.RESTORE }.also { history ->
            assertThat(history.memberPassId).isEqualTo(existingPass.id)
            assertThat(history.reservationId).isEqualTo(existingReservationId)
            assertThat(history.reason).isEqualTo(PassUsageHistoryReason.RESERVATION_CANCELED)
            assertThat(history.changedCount).isEqualTo(1)
            assertThat(history.remainingCountAfter).isEqualTo(10)
        }
        if (creationCommitted) {
            val createdReservation = confirmedReservations.single()
            histories.single { it.type == PassUsageHistoryType.CONSUME }.also { history ->
                assertThat(history.memberPassId).isEqualTo(racingPass.id)
                assertThat(history.reservationId).isEqualTo(createdReservation.id)
                assertThat(history.reason).isEqualTo(PassUsageHistoryReason.RESERVATION_CONFIRMED)
                assertThat(history.changedCount).isEqualTo(-1)
                assertThat(history.remainingCountAfter).isEqualTo(9)
            }
        }

        val outboxEvents = outboxEventJpaRepository.findAll()
        assertThat(outboxEvents).hasSize(1 + if (creationCommitted) 1 else 0)
        assertThat(outboxEvents.map { it.status }).containsOnly(OutboxEventStatus.PENDING)
        assertThat(outboxEvents.count { it.eventType == "ReservationCanceledEvent" }).isEqualTo(1)
        assertThat(outboxEvents.count { it.eventType == "ReservationConfirmedEvent" })
            .isEqualTo(if (creationCommitted) 1 else 0)
        assertThat(outboxEvents.single { it.eventType == "ReservationCanceledEvent" }.aggregateId)
            .isEqualTo(existingReservationId)
        if (creationCommitted) {
            assertThat(outboxEvents.single { it.eventType == "ReservationConfirmedEvent" }.aggregateId)
                .isEqualTo(confirmedReservations.single().id)
        }
    }

    private fun cancelReservationResult(token: String, reservationId: Long): ReservationCancelResult {
        val response = mockMvc.patch("/api/v1/reservations/$reservationId/cancel") {
            header("Authorization", "Bearer $token")
        }.andReturn().response
        val code = response.contentAsString
            .takeIf { it.isNotBlank() }
            ?.let { objectMapper.readTree(it)["code"]?.asText() }
        return ReservationCancelResult(status = response.status, code = code)
    }

    private fun postReservationResult(token: String, memberId: Long, classSessionId: Long): ReservationCreationResult {
        val response = mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"memberId":$memberId,"classSessionId":$classSessionId}"""
        }.andReturn().response
        val code = response.contentAsString
            .takeIf { it.isNotBlank() }
            ?.let { objectMapper.readTree(it)["code"]?.asText() }
        return ReservationCreationResult(status = response.status, code = code)
    }

    private fun org.springframework.test.web.servlet.MockMvcResultMatchersDsl.expectReservationCancelErrorShape(
        status: Int,
        code: String,
        message: String,
        reservationId: Long,
    ) {
        jsonPath("$.timestamp") { exists() }
        jsonPath("$.status") { value(status) }
        jsonPath("$.code") { value(code) }
        jsonPath("$.message") { value(message) }
        jsonPath("$.path") { value("/api/v1/reservations/$reservationId/cancel") }
        jsonPath("$.traceId") { value(RESERVATION_CANCEL_TRACE_ID) }
        jsonPath("$.details") { doesNotExist() }
        jsonPath("$.stackTrace") { doesNotExist() }
    }

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
        capacity: Int = 10,
        reservedCount: Int = 0,
    ): ClassSessionJpaEntity =
        classSessionJpaRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = "Morning Bouldering",
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse("2026-05-10T11:00:00Z"),
                capacity = capacity,
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
        const val RESERVATION_CANCEL_TRACE_ID = "trace-reservation-cancel"
    }
}

private interface ReservationResult {
    val status: Int
    val code: String?
}

private data class ReservationCancelResult(
    override val status: Int,
    override val code: String?,
) : ReservationResult

private data class ReservationCreationResult(
    override val status: Int,
    override val code: String?,
) : ReservationResult
