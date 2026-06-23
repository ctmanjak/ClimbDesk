package dev.climbdesk.classsession.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.auth.infrastructure.adapter.Pbkdf2PasswordVerifier
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaEntity
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaEntity
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

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
class ClassSessionQueryIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val classSessionJpaRepository: ClassSessionJpaRepository,
) {
    @BeforeEach
    fun setUp() {
        classSessionJpaRepository.deleteAll()
        adminUserJpaRepository.deleteAll()
    }

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can list class sessions`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val first = saveClassSession(title = "First Session", startsAt = Instant.parse("2026-05-12T10:00:00Z"))
        saveClassSession(title = "Second Session", startsAt = Instant.parse("2026-05-11T10:00:00Z"))
        val third = saveClassSession(
            title = "Third Session",
            startsAt = Instant.parse("2026-05-13T10:00:00Z"),
            reservedCount = 3,
        )

        mockMvc.get("/api/v1/class-sessions") {
            param("page", "0")
            param("size", "2")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].id") { value(third.id) }
            jsonPath("$.items[0].title") { value("Third Session") }
            jsonPath("$.items[0].reservedCount") { value(3) }
            jsonPath("$.items[0].status") { value("OPEN") }
            jsonPath("$.items[1].id") { value(first.id) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(2) }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.totalPages") { value(2) }
        }
    }

    @Test
    fun `class session list uses default paging and starts at descending ordering`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val later = saveClassSession(title = "Later Session", startsAt = Instant.parse("2026-05-12T10:00:00Z"))
        val earlier = saveClassSession(title = "Earlier Session", startsAt = Instant.parse("2026-05-11T10:00:00Z"))

        mockMvc.get("/api/v1/class-sessions") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].id") { value(later.id) }
            jsonPath("$.items[1].id") { value(earlier.id) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(20) }
            jsonPath("$.totalElements") { value(2) }
            jsonPath("$.totalPages") { value(1) }
        }
    }

    @Test
    fun `class session list uses id descending ordering when start times are equal`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val first = saveClassSession(title = "First Session")
        val second = saveClassSession(title = "Second Session")

        mockMvc.get("/api/v1/class-sessions") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(second.id) }
            jsonPath("$.items[1].id") { value(first.id) }
        }
    }

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can retrieve class session detail`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val classSession = saveClassSession(title = "Beginner Bouldering", reservedCount = 2)

        mockMvc.get("/api/v1/class-sessions/${classSession.id}") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(classSession.id) }
            jsonPath("$.title") { value("Beginner Bouldering") }
            jsonPath("$.startsAt") { value("2026-05-10T10:00:00Z") }
            jsonPath("$.endsAt") { value("2026-05-10T11:00:00Z") }
            jsonPath("$.capacity") { value(12) }
            jsonPath("$.reservedCount") { value(2) }
            jsonPath("$.status") { value("OPEN") }
            jsonPath("$.createdAt") { isNotEmpty() }
            jsonPath("$.canceledAt") { value(null) }
            jsonPath("$.affectedReservationCount") { value(0) }
        }
    }

    @Test
    fun `missing class session returns class session not found`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.get("/api/v1/class-sessions/${Long.MAX_VALUE}") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("CLASS_SESSION_NOT_FOUND") }
            jsonPath("$.message") { value("Class session not found.") }
        }
    }

    @Test
    fun `class session list rejects invalid paging`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.get("/api/v1/class-sessions") {
            param("page", "-1")
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }

        mockMvc.get("/api/v1/class-sessions") {
            param("size", "0")
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }

        mockMvc.get("/api/v1/class-sessions") {
            param("size", "101")
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `class session list requires jwt authorization`() {
        mockMvc.get("/api/v1/class-sessions")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    @Test
    fun `class session detail requires jwt authorization`() {
        val classSession = saveClassSession(title = "Beginner Bouldering")

        mockMvc.get("/api/v1/class-sessions/${classSession.id}")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    private fun saveClassSession(
        title: String,
        startsAt: Instant = Instant.parse("2026-05-10T10:00:00Z"),
        reservedCount: Int = 0,
    ): ClassSessionJpaEntity =
        classSessionJpaRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = title,
                startsAt = startsAt,
                endsAt = startsAt.plusSeconds(3600),
                capacity = 12,
                reservedCount = reservedCount,
                status = ClassSessionStatus.OPEN,
            ),
        )

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
}
