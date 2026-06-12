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
import dev.climbdesk.reservation.application.CreateReservationCommand
import dev.climbdesk.reservation.application.ReservationApplicationService
import dev.climbdesk.reservation.domain.ReservationStatus
import dev.climbdesk.reservation.infrastructure.persistence.ReservationJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

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
class ReservationCreationIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val reservationApplicationService: ReservationApplicationService,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
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
    fun `manager and staff can create reservation`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession(capacity = 12, reservedCount = 0)
        val firstPass = saveMemberPass(member, remainingCount = 3, issuedAt = Instant.parse("2026-05-01T00:00:00Z"))
        saveMemberPass(member, remainingCount = 10, issuedAt = Instant.parse("2026-05-02T00:00:00Z"))

        val response = mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"memberId":${member.id},"classSessionId":${classSession.id}}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.memberId") { value(member.id) }
            jsonPath("$.classSessionId") { value(classSession.id) }
            jsonPath("$.memberPassId") { value(firstPass.id) }
            jsonPath("$.status") { value("CONFIRMED") }
            jsonPath("$.reservedAt") { isNotEmpty() }
            jsonPath("$.canceledAt") { value(null) }
            jsonPath("$.cancelReason") { value(null) }
            jsonPath("$.classSession.id") { value(classSession.id) }
            jsonPath("$.classSession.capacity") { value(12) }
            jsonPath("$.classSession.reservedCount") { value(1) }
            jsonPath("$.classSession.status") { value("OPEN") }
            jsonPath("$.memberPass.id") { value(firstPass.id) }
            jsonPath("$.memberPass.remainingCount") { value(2) }
            jsonPath("$.memberPass.status") { value("ACTIVE") }
        }.andReturn().response.contentAsString

        val reservationId = objectMapper.readTree(response)["id"].asLong()
        val reservation = reservationJpaRepository.findById(reservationId).orElseThrow()
        val savedClassSession = classSessionJpaRepository.findById(classSession.id).orElseThrow()
        val savedMemberPass = memberPassJpaRepository.findById(firstPass.id).orElseThrow()
        val usageHistory = passUsageHistoryJpaRepository.findAll().single()
        val outboxEvent = outboxEventJpaRepository.findAll().single()

        assertThat(reservation.status).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(savedClassSession.reservedCount).isEqualTo(1)
        assertThat(savedMemberPass.remainingCount).isEqualTo(2)
        assertThat(usageHistory.memberPassId).isEqualTo(firstPass.id)
        assertThat(usageHistory.reservationId).isEqualTo(reservationId)
        assertThat(usageHistory.type).isEqualTo(PassUsageHistoryType.CONSUME)
        assertThat(usageHistory.reason).isEqualTo(PassUsageHistoryReason.RESERVATION_CONFIRMED)
        assertThat(usageHistory.changedCount).isEqualTo(-1)
        assertThat(usageHistory.remainingCountAfter).isEqualTo(2)
        assertThat(outboxEvent.eventType).isEqualTo("ReservationConfirmedEvent")
        assertThat(outboxEvent.aggregateType).isEqualTo("Reservation")
        assertThat(outboxEvent.aggregateId).isEqualTo(reservationId)
        assertThat(outboxEvent.status).isEqualTo(OutboxEventStatus.PENDING)
    }

    @Test
    fun `inactive member fails with member inactive`() {
        val token = accessTokenFor("manager-inactive@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.INACTIVE, deactivatedAt = Instant.parse("2026-05-01T00:00:00Z"))
        val classSession = saveClassSession()
        val memberPass = saveMemberPass(member)

        postReservation(token, member.id, classSession.id)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("MEMBER_INACTIVE") }
                expectReservationErrorShape(
                    status = 409,
                    code = "MEMBER_INACTIVE",
                    message = "Member is inactive.",
                )
            }

        assertNoReservationSideEffects(classSession.id, memberPass.id)
    }

    @Test
    fun `missing member fails with member not found`() {
        val token = accessTokenFor("manager-missing-member@climbdesk.local", AdminUserRole.MANAGER)
        val classSession = saveClassSession()

        postReservation(token, 9223372036854775807, classSession.id)
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("MEMBER_NOT_FOUND") }
                expectReservationErrorShape(
                    status = 404,
                    code = "MEMBER_NOT_FOUND",
                    message = "Member not found.",
                )
            }

        assertNoReservationSideEffects(classSession.id)
    }

    @Test
    fun `missing class session fails with class session not found`() {
        val token = accessTokenFor("manager-missing-class@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val memberPass = saveMemberPass(member)

        postReservation(token, member.id, 9223372036854775807)
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("CLASS_SESSION_NOT_FOUND") }
                expectReservationErrorShape(
                    status = 404,
                    code = "CLASS_SESSION_NOT_FOUND",
                    message = "Class session not found.",
                )
            }

        assertThat(reservationJpaRepository.count()).isZero()
        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(10)
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
    }

    @Test
    fun `non open class session fails with class session not open`() {
        val token = accessTokenFor("manager-canceled-class@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession(status = ClassSessionStatus.CANCELED, canceledAt = Instant.now())
        val memberPass = saveMemberPass(member)

        postReservation(token, member.id, classSession.id)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("CLASS_SESSION_NOT_OPEN") }
                expectReservationErrorShape(
                    status = 409,
                    code = "CLASS_SESSION_NOT_OPEN",
                    message = "Class session is not open.",
                )
            }

        assertNoReservationSideEffects(classSession.id, memberPass.id)
    }

    @Test
    fun `closed class session fails with class session not open`() {
        val token = accessTokenFor("manager-closed-class@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession(status = ClassSessionStatus.CLOSED)
        val memberPass = saveMemberPass(member)

        postReservation(token, member.id, classSession.id)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("CLASS_SESSION_NOT_OPEN") }
                expectReservationErrorShape(
                    status = 409,
                    code = "CLASS_SESSION_NOT_OPEN",
                    message = "Class session is not open.",
                )
            }

        assertNoReservationSideEffects(classSession.id, memberPass.id)
    }

    @Test
    fun `full class session fails with class session full`() {
        val token = accessTokenFor("manager-full-class@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession(capacity = 1, reservedCount = 1)
        val memberPass = saveMemberPass(member)

        postReservation(token, member.id, classSession.id)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("CLASS_SESSION_FULL") }
                expectReservationErrorShape(
                    status = 409,
                    code = "CLASS_SESSION_FULL",
                    message = "Class session is full.",
                )
            }

        assertNoReservationSideEffects(classSession.id, memberPass.id, expectedReservedCount = 1)
    }

    @Test
    fun `duplicate confirmed reservation fails with duplicate reservation`() {
        val token = accessTokenFor("manager-duplicate@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 5)
        insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)

        postReservation(token, member.id, classSession.id)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("DUPLICATE_RESERVATION") }
                expectReservationErrorShape(
                    status = 409,
                    code = "DUPLICATE_RESERVATION",
                    message = "Member already has a confirmed reservation for this class session.",
                )
            }

        assertThat(reservationJpaRepository.count()).isEqualTo(1)
        assertThat(memberPassJpaRepository.findById(memberPass.id).orElseThrow().remainingCount).isEqualTo(5)
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isEqualTo(1)
    }

    @Test
    fun `canceled reservation history does not block re reservation`() {
        val token = accessTokenFor("manager-canceled-history@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession()
        val oldPass = saveMemberPass(member, remainingCount = 5)
        insertReservation(member.id, classSession.id, oldPass.id, ReservationStatus.CANCELED)
        saveMemberPass(member, remainingCount = 5, issuedAt = Instant.parse("2026-05-02T00:00:00Z"))

        mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"memberId":${member.id},"classSessionId":${classSession.id}}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.memberPassId") { value(oldPass.id) }
        }

        assertThat(reservationJpaRepository.count()).isEqualTo(2)
    }

    @Test
    fun `reservation selects available member pass by expires at issued at and id`() {
        val token = accessTokenFor("manager-pass-order@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession()
        saveMemberPass(
            member = member,
            remainingCount = 5,
            issuedAt = Instant.parse("2026-05-01T00:00:00Z"),
            expiresAt = null,
        )
        saveMemberPass(
            member = member,
            remainingCount = 5,
            issuedAt = Instant.parse("2026-05-01T00:00:00Z"),
            expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
        )
        val selectedPass = saveMemberPass(
            member = member,
            remainingCount = 5,
            issuedAt = Instant.parse("2026-05-03T00:00:00Z"),
            expiresAt = Instant.parse("2026-07-20T00:00:00Z"),
        )

        mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"memberId":${member.id},"classSessionId":${classSession.id}}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.memberPassId") { value(selectedPass.id) }
            jsonPath("$.memberPass.remainingCount") { value(4) }
        }
    }

    @Test
    fun `missing available pass fails with member pass not available`() {
        val token = accessTokenFor("manager-no-pass@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession()

        postReservation(token, member.id, classSession.id)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("MEMBER_PASS_NOT_AVAILABLE") }
                expectReservationErrorShape(
                    status = 409,
                    code = "MEMBER_PASS_NOT_AVAILABLE",
                    message = "Member pass is not available.",
                )
            }

        assertNoReservationSideEffects(classSession.id)
    }

    @Test
    fun `reservation creation rejects invalid request body`() {
        val token = accessTokenFor("manager-invalid-request@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"memberId":0,"classSessionId":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `reservation creation requires jwt authorization`() {
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession()
        saveMemberPass(member)

        mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"memberId":${member.id},"classSessionId":${classSession.id}}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
    }

    @Test
    fun `concurrent reservation requests do not exceed class session capacity`() {
        val token = accessTokenFor("manager-concurrent-capacity@climbdesk.local", AdminUserRole.MANAGER)
        val classSession = saveClassSession(capacity = 10)
        val members = (1..30).map {
            saveMember(status = MemberStatus.ACTIVE)
        }
        members.forEach { member ->
            saveMemberPass(member, remainingCount = 1)
        }

        val results = TestConcurrencyUtils.runConcurrently(
            *members.map { member ->
                { postReservationResult(token, member.id, classSession.id) }
            }.toTypedArray(),
        )

        assertThat(results.count { it.status == 201 }).isEqualTo(10)
        assertThat(results.count { it.status == 409 }).isEqualTo(20)
        assertThat(results.filter { it.status == 409 }.map { it.code }).containsOnly("CLASS_SESSION_FULL")
        assertThat(reservationJpaRepository.count()).isEqualTo(10)
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isEqualTo(10)
        assertThat(memberPassJpaRepository.findAll()).allSatisfy { memberPass ->
            assertThat(memberPass.remainingCount).isGreaterThanOrEqualTo(0)
        }
        assertThat(passUsageHistoryJpaRepository.count()).isEqualTo(10)
        assertThat(outboxEventJpaRepository.count()).isEqualTo(10)
    }

    @Test
    fun `concurrent duplicate reservation requests create only one confirmed reservation`() {
        val token = accessTokenFor("manager-concurrent-duplicate@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession(capacity = 2)
        saveMemberPass(member, remainingCount = 5)

        val results = TestConcurrencyUtils.runConcurrently(
            { postReservationResult(token, member.id, classSession.id) },
            { postReservationResult(token, member.id, classSession.id) },
        )

        assertThat(results.map { it.status }).containsExactlyInAnyOrder(201, 409)
        assertThat(results.single { it.status == 409 }.code).isEqualTo("DUPLICATE_RESERVATION")
        assertThat(
            reservationJpaRepository.existsByMemberIdAndClassSessionIdAndStatus(
                member.id,
                classSession.id,
                ReservationStatus.CONFIRMED,
            ),
        ).isTrue()
        assertThat(reservationJpaRepository.count()).isEqualTo(1)
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isEqualTo(1)
        assertThat(memberPassJpaRepository.findAll().single().remainingCount).isEqualTo(4)
        assertThat(passUsageHistoryJpaRepository.count()).isEqualTo(1)
        assertThat(outboxEventJpaRepository.count()).isEqualTo(1)
    }

    @Test
    fun `reservation creation lock timeout on class session row fails without side effects`() {
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession()
        val memberPass = saveMemberPass(member, remainingCount = 10)

        holdRowLock(Table.CLASS_SESSIONS, classSession.id).use {
            assertThatThrownBy {
                runWithShortLockTimeout {
                    reservationApplicationService.reserveClass(
                        CreateReservationCommand(memberId = member.id, classSessionId = classSession.id),
                    )
                }
            }.isInstanceOf(PessimisticLockingFailureException::class.java)
        }

        assertThat(reservationJpaRepository.count()).isZero()
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isZero()
        memberPassJpaRepository.findById(memberPass.id).orElseThrow().also { savedMemberPass ->
            assertThat(savedMemberPass.remainingCount).isEqualTo(10)
            assertThat(savedMemberPass.version).isEqualTo(memberPass.version)
        }
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
    }

    @Test
    fun `reservation cancellation lock timeout on reservation row fails without side effects`() {
        val member = saveMember(status = MemberStatus.ACTIVE)
        val classSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 9)
        val reservationId = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)

        holdRowLock(Table.RESERVATIONS, reservationId).use {
            assertThatThrownBy {
                runWithShortLockTimeout {
                    reservationApplicationService.cancelReservation(reservationId)
                }
            }.isInstanceOf(PessimisticLockingFailureException::class.java)
        }

        reservationJpaRepository.findById(reservationId).orElseThrow().also { reservation ->
            assertThat(reservation.status).isEqualTo(ReservationStatus.CONFIRMED)
            assertThat(reservation.canceledAt).isNull()
            assertThat(reservation.cancelReason).isNull()
        }
        assertThat(classSessionJpaRepository.findById(classSession.id).orElseThrow().reservedCount).isEqualTo(1)
        memberPassJpaRepository.findById(memberPass.id).orElseThrow().also { savedMemberPass ->
            assertThat(savedMemberPass.remainingCount).isEqualTo(9)
            assertThat(savedMemberPass.version).isEqualTo(memberPass.version)
        }
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
    }

    private fun postReservation(token: String, memberId: Long, classSessionId: Long) =
        mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            header("X-Trace-Id", RESERVATION_CREATE_TRACE_ID)
            content = """{"memberId":$memberId,"classSessionId":$classSessionId}"""
        }

    private fun org.springframework.test.web.servlet.MockMvcResultMatchersDsl.expectReservationErrorShape(
        status: Int,
        code: String,
        message: String,
    ) {
        jsonPath("$.timestamp") { exists() }
        jsonPath("$.status") { value(status) }
        jsonPath("$.code") { value(code) }
        jsonPath("$.message") { value(message) }
        jsonPath("$.path") { value("/api/v1/reservations") }
        jsonPath("$.traceId") { value(RESERVATION_CREATE_TRACE_ID) }
        jsonPath("$.details") { doesNotExist() }
        jsonPath("$.stackTrace") { doesNotExist() }
    }

    private fun postReservationResult(token: String, memberId: Long, classSessionId: Long): ReservationPostResult {
        val response = postReservation(token, memberId, classSessionId)
            .andReturn()
            .response
        val code = response.contentAsString
            .takeIf { it.isNotBlank() }
            ?.let { objectMapper.readTree(it)["code"]?.asText() }
        return ReservationPostResult(status = response.status, code = code)
    }

    private fun assertNoReservationSideEffects(
        classSessionId: Long,
        memberPassId: Long? = null,
        expectedReservedCount: Int = 0,
    ) {
        assertThat(reservationJpaRepository.count()).isZero()
        assertThat(passUsageHistoryJpaRepository.count()).isZero()
        assertThat(outboxEventJpaRepository.count()).isZero()
        assertThat(classSessionJpaRepository.findById(classSessionId).orElseThrow().reservedCount)
            .isEqualTo(expectedReservedCount)
        memberPassId?.let {
            assertThat(memberPassJpaRepository.findById(it).orElseThrow().remainingCount).isEqualTo(10)
        }
    }

    private fun runWithShortLockTimeout(operation: () -> Unit) {
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }.executeWithoutResult {
            jdbcTemplate.execute("set local lock_timeout = '200ms'")
            operation()
        }
    }

    private fun holdRowLock(table: Table, rowId: Long): HeldRowLock {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        Thread {
            try {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    connection.prepareStatement("select id from ${table.tableName} where id = ? for update").use { statement ->
                        statement.setLong(1, rowId)
                        statement.executeQuery().use { resultSet ->
                            check(resultSet.next()) { "No ${table.tableName} row found for id $rowId." }
                        }
                    }
                    locked.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release row lock." }
                    connection.rollback()
                }
            } catch (throwable: Throwable) {
                failure.set(throwable)
                locked.countDown()
            } finally {
                finished.countDown()
            }
        }.apply {
            name = "reservation-lock-holder-${table.tableName}-$rowId"
            start()
        }

        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue()
        failure.get()?.let { throw AssertionError("Failed to acquire ${table.tableName} row lock.", it) }

        return HeldRowLock(release = release, finished = finished, failure = failure)
    }

    private fun saveMember(
        status: MemberStatus,
        deactivatedAt: Instant? = null,
    ): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Hong Gil Dong",
                phone = "010${memberSequence.getAndIncrement()}".padEnd(11, '0'),
                email = null,
                status = status,
                deactivatedAt = deactivatedAt,
            ),
        )

    private fun saveClassSession(
        capacity: Int = 10,
        reservedCount: Int = 0,
        status: ClassSessionStatus = ClassSessionStatus.OPEN,
        canceledAt: Instant? = null,
    ): ClassSessionJpaEntity =
        classSessionJpaRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = "Morning Bouldering",
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse("2026-05-10T11:00:00Z"),
                capacity = capacity,
                reservedCount = reservedCount,
                status = status,
                canceledAt = canceledAt,
                cancelReason = canceledAt?.let { "Class canceled" },
            ),
        )

    private fun saveMemberPass(
        member: MemberJpaEntity,
        remainingCount: Int = 10,
        issuedAt: Instant = Instant.parse("2026-05-01T00:00:00Z"),
        expiresAt: Instant? = issuedAt.plus(90, ChronoUnit.DAYS),
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
                issuedAt = issuedAt,
                expiresAt = expiresAt,
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
        val memberSequence = AtomicInteger(10000000)
        const val RESERVATION_CREATE_TRACE_ID = "trace-reservation-create"
    }

    private enum class Table(val tableName: String) {
        CLASS_SESSIONS("class_sessions"),
        RESERVATIONS("reservations"),
    }

    private class HeldRowLock(
        private val release: CountDownLatch,
        private val finished: CountDownLatch,
        private val failure: AtomicReference<Throwable?>,
    ) : AutoCloseable {
        override fun close() {
            release.countDown()
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
            failure.get()?.let { throw AssertionError("Row lock holder failed.", it) }
        }
    }
}

private data class ReservationPostResult(
    val status: Int,
    val code: String?,
)
