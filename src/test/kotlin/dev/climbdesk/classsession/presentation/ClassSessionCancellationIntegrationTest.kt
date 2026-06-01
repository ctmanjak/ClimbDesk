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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
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
class ClassSessionCancellationIntegrationTest @Autowired constructor(
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
    fun `manager and staff can cancel open class session`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val classSession = saveClassSession(reservedCount = 2)

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
            jsonPath("$.affectedReservationCount") { value(2) }
        }

        val canceled = classSessionJpaRepository.findById(classSession.id).orElseThrow()
        assertThat(canceled.status).isEqualTo(ClassSessionStatus.CANCELED)
        assertThat(canceled.reservedCount).isZero()
        assertThat(canceled.canceledAt).isNotNull()
        assertThat(canceled.cancelReason).isEqualTo("Operational issue")
        assertThat(canceled.affectedReservationCount).isEqualTo(2)
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

    private fun saveClassSession(reservedCount: Int = 0): ClassSessionJpaEntity =
        classSessionJpaRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = "Beginner Bouldering",
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse("2026-05-10T11:00:00Z"),
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
