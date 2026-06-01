package dev.climbdesk.classsession.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.auth.infrastructure.adapter.Pbkdf2PasswordVerifier
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaEntity
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
import dev.climbdesk.classsession.domain.ClassSessionStatus
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
class ClassSessionCreationIntegrationTest @Autowired constructor(
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
    fun `manager and staff can create class session`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)

        val response = mockMvc.post("/api/v1/class-sessions") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = validRequest
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.title") { value("Beginner Bouldering") }
            jsonPath("$.startsAt") { value("2026-05-10T10:00:00Z") }
            jsonPath("$.endsAt") { value("2026-05-10T11:00:00Z") }
            jsonPath("$.capacity") { value(12) }
            jsonPath("$.reservedCount") { value(0) }
            jsonPath("$.status") { value("OPEN") }
            jsonPath("$.createdAt") { isNotEmpty() }
            jsonPath("$.canceledAt") { value(null) }
            jsonPath("$.affectedReservationCount") { value(0) }
        }.andReturn().response.contentAsString

        val createdId = objectMapper.readTree(response)["id"].asLong()
        val created = classSessionJpaRepository.findById(createdId).orElseThrow()
        assertThat(created.reservedCount).isZero()
        assertThat(created.status).isEqualTo(ClassSessionStatus.OPEN)
        assertThat(created.affectedReservationCount).isZero()
        assertThat(created.canceledAt).isNull()
        assertThat(created.cancelReason).isNull()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `class session create rejects non-positive capacity`(capacity: Int) {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/class-sessions") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"title":"Beginner Bouldering","startsAt":"2026-05-10T10:00:00Z","endsAt":"2026-05-10T11:00:00Z","capacity":$capacity}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.details[0].field") { value("capacity") }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-05-10T10:00:00Z", "2026-05-10T09:00:00Z"])
    fun `class session create rejects time range without positive duration`(endsAt: String) {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/class-sessions") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"title":"Beginner Bouldering","startsAt":"2026-05-10T10:00:00Z","endsAt":"$endsAt","capacity":12}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `class session create requires jwt authorization`() {
        mockMvc.post("/api/v1/class-sessions") {
            contentType = MediaType.APPLICATION_JSON
            content = validRequest
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
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

    companion object {
        private val validRequest =
            """{"title":"Beginner Bouldering","startsAt":"2026-05-10T10:00:00Z","endsAt":"2026-05-10T11:00:00Z","capacity":12}"""
    }
}
