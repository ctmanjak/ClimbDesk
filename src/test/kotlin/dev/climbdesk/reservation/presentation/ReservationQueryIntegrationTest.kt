package dev.climbdesk.reservation.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.auth.infrastructure.adapter.Pbkdf2PasswordVerifier
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaEntity
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaEntity
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaRepository
import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassProductType
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaRepository
import dev.climbdesk.reservation.domain.ReservationStatus
import dev.climbdesk.reservation.infrastructure.persistence.ReservationJpaRepository
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
import org.springframework.test.web.servlet.get
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
class ReservationQueryIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
    private val memberPassJpaRepository: MemberPassJpaRepository,
    private val classSessionJpaRepository: ClassSessionJpaRepository,
    private val reservationJpaRepository: ReservationJpaRepository,
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
    fun `manager and staff can list reservations`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val member = saveMember()
        val classSession = saveClassSession(reservedCount = 2)
        val memberPass = saveMemberPass(member, remainingCount = 8)
        val older = insertReservation(
            memberId = member.id,
            classSessionId = classSession.id,
            memberPassId = memberPass.id,
            status = ReservationStatus.CONFIRMED,
            reservedAt = Instant.parse("2026-05-05T00:00:00Z"),
        )
        val newer = insertReservation(
            memberId = member.id,
            classSessionId = classSession.id,
            memberPassId = memberPass.id,
            status = ReservationStatus.CANCELED,
            reservedAt = Instant.parse("2026-05-06T00:00:00Z"),
        )

        mockMvc.get("/api/v1/reservations") {
            param("page", "0")
            param("size", "1")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(newer) }
            jsonPath("$.items[0].memberId") { value(member.id) }
            jsonPath("$.items[0].classSessionId") { value(classSession.id) }
            jsonPath("$.items[0].memberPassId") { value(memberPass.id) }
            jsonPath("$.items[0].status") { value("CANCELED") }
            jsonPath("$.items[0].reservedAt") { value("2026-05-06T00:00:00Z") }
            jsonPath("$.items[0].canceledAt") { value("2026-05-06T01:00:00Z") }
            jsonPath("$.items[0].cancelReason") { value("USER_REQUESTED") }
            jsonPath("$.items[0].classSession.id") { value(classSession.id) }
            jsonPath("$.items[0].classSession.capacity") { value(12) }
            jsonPath("$.items[0].classSession.reservedCount") { value(2) }
            jsonPath("$.items[0].classSession.status") { value("OPEN") }
            jsonPath("$.items[0].memberPass.id") { value(memberPass.id) }
            jsonPath("$.items[0].memberPass.remainingCount") { value(8) }
            jsonPath("$.items[0].memberPass.status") { value("ACTIVE") }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(1) }
            jsonPath("$.totalElements") { value(2) }
            jsonPath("$.totalPages") { value(2) }
        }

        mockMvc.get("/api/v1/reservations") {
            param("page", "1")
            param("size", "1")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(older) }
        }
    }

    @Test
    fun `reservation list uses default paging`() {
        val token = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember()
        val classSession = saveClassSession()
        val memberPass = saveMemberPass(member)
        insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)

        mockMvc.get("/api/v1/reservations") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(20) }
            jsonPath("$.totalElements") { value(1) }
            jsonPath("$.totalPages") { value(1) }
        }
    }

    @Test
    fun `reservation list filters by member class session and status independently and together`() {
        val token = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val firstMember = saveMember()
        val secondMember = saveMember()
        val firstClassSession = saveClassSession()
        val secondClassSession = saveClassSession()
        val firstPass = saveMemberPass(firstMember)
        val secondPass = saveMemberPass(secondMember)
        val matching = insertReservation(
            memberId = firstMember.id,
            classSessionId = firstClassSession.id,
            memberPassId = firstPass.id,
            status = ReservationStatus.CONFIRMED,
            reservedAt = Instant.parse("2026-05-07T00:00:00Z"),
        )
        val sameMemberCanceled = insertReservation(
            memberId = firstMember.id,
            classSessionId = secondClassSession.id,
            memberPassId = firstPass.id,
            status = ReservationStatus.CANCELED,
            reservedAt = Instant.parse("2026-05-08T00:00:00Z"),
        )
        val sameClassOtherMember = insertReservation(
            memberId = secondMember.id,
            classSessionId = firstClassSession.id,
            memberPassId = secondPass.id,
            status = ReservationStatus.CONFIRMED,
            reservedAt = Instant.parse("2026-05-09T00:00:00Z"),
        )

        mockMvc.get("/api/v1/reservations") {
            param("memberId", firstMember.id.toString())
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].id") { value(sameMemberCanceled) }
            jsonPath("$.items[1].id") { value(matching) }
        }

        mockMvc.get("/api/v1/reservations") {
            param("classSessionId", firstClassSession.id.toString())
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].id") { value(sameClassOtherMember) }
            jsonPath("$.items[1].id") { value(matching) }
        }

        mockMvc.get("/api/v1/reservations") {
            param("status", "CANCELED")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(sameMemberCanceled) }
        }

        mockMvc.get("/api/v1/reservations") {
            param("memberId", firstMember.id.toString())
            param("classSessionId", firstClassSession.id.toString())
            param("status", "CONFIRMED")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(matching) }
        }
    }

    @Test
    fun `reservation list orders by reserved at descending and id descending`() {
        val token = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember()
        val classSession = saveClassSession()
        val memberPass = saveMemberPass(member)
        val first = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CONFIRMED)
        val second = insertReservation(member.id, classSession.id, memberPass.id, ReservationStatus.CANCELED)

        mockMvc.get("/api/v1/reservations") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(second) }
            jsonPath("$.items[1].id") { value(first) }
        }
    }

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can retrieve reservation detail`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val member = saveMember()
        val classSession = saveClassSession(reservedCount = 1)
        val memberPass = saveMemberPass(member, remainingCount = 9)
        val reservationId = insertReservation(
            memberId = member.id,
            classSessionId = classSession.id,
            memberPassId = memberPass.id,
            status = ReservationStatus.CONFIRMED,
        )

        mockMvc.get("/api/v1/reservations/$reservationId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(reservationId) }
            jsonPath("$.memberId") { value(member.id) }
            jsonPath("$.classSessionId") { value(classSession.id) }
            jsonPath("$.memberPassId") { value(memberPass.id) }
            jsonPath("$.status") { value("CONFIRMED") }
            jsonPath("$.reservedAt") { value("2026-05-05T00:00:00Z") }
            jsonPath("$.canceledAt") { value(null) }
            jsonPath("$.cancelReason") { value(null) }
            jsonPath("$.classSession.id") { value(classSession.id) }
            jsonPath("$.classSession.capacity") { value(12) }
            jsonPath("$.classSession.reservedCount") { value(1) }
            jsonPath("$.classSession.status") { value("OPEN") }
            jsonPath("$.memberPass.id") { value(memberPass.id) }
            jsonPath("$.memberPass.remainingCount") { value(9) }
            jsonPath("$.memberPass.status") { value("ACTIVE") }
        }
    }

    @Test
    fun `missing reservation detail returns reservation not found`() {
        val token = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.get("/api/v1/reservations/${Long.MAX_VALUE}") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESERVATION_NOT_FOUND") }
            jsonPath("$.message") { value("Reservation not found.") }
        }
    }

    @Test
    fun `reservation list rejects invalid paging`() {
        val token = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.get("/api/v1/reservations") {
            param("page", "-1")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }

        mockMvc.get("/api/v1/reservations") {
            param("size", "0")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }

        mockMvc.get("/api/v1/reservations") {
            param("size", "101")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `reservation list rejects invalid status filter`() {
        val token = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.get("/api/v1/reservations") {
            param("status", "INVALID")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `reservation query APIs require jwt authorization`() {
        mockMvc.get("/api/v1/reservations")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }

        mockMvc.get("/api/v1/reservations/1")
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
                capacity = 12,
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
                expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
            ),
        )
    }

    private fun insertReservation(
        memberId: Long,
        classSessionId: Long,
        memberPassId: Long,
        status: ReservationStatus,
        reservedAt: Instant = Instant.parse("2026-05-05T00:00:00Z"),
    ): Long =
        jdbcTemplate.queryForObject(
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
            Timestamp.from(reservedAt),
            if (status == ReservationStatus.CANCELED) Timestamp.from(reservedAt.plus(1, ChronoUnit.HOURS)) else null,
            if (status == ReservationStatus.CANCELED) "USER_REQUESTED" else null,
            Timestamp.from(reservedAt),
            Timestamp.from(reservedAt),
        ) ?: error("reservation id was not returned")

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
        reservationJpaRepository.deleteAll()
        classSessionJpaRepository.deleteAll()
        memberPassJpaRepository.deleteAll()
        passProductJpaRepository.deleteAll()
        memberJpaRepository.deleteAll()
        adminUserJpaRepository.deleteAll()
    }

    private companion object {
        val memberSequence = AtomicInteger(20000000)
    }
}
