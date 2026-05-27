package dev.climbdesk.auth.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.auth.infrastructure.adapter.Pbkdf2PasswordVerifier
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaEntity
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Testcontainers

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
class AuthLoginIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val adminUserJpaRepository: AdminUserJpaRepository,
) {
    @BeforeEach
    fun setUp() {
        adminUserJpaRepository.deleteAll()
    }

    @Test
    fun `active manager can log in with persisted admin user`() {
        val passwordHash = Pbkdf2PasswordVerifier.encode("password1234")
        val adminUser = adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "manager@climbdesk.local",
                passwordHash = passwordHash,
                role = AdminUserRole.MANAGER,
                status = AdminUserStatus.ACTIVE,
            ),
        )

        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"manager@climbdesk.local","password":"password1234"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { isNotEmpty() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresIn") { value(3600) }
            jsonPath("$.adminUser.id") { value(adminUser.id) }
            jsonPath("$.adminUser.email") { value("manager@climbdesk.local") }
            jsonPath("$.adminUser.role") { value("MANAGER") }
            jsonPath("$.adminUser.status") { value("ACTIVE") }
        }.andReturn().response.contentAsString

        val responseBody = objectMapper.readTree(response)
        assertThat(responseBody["accessToken"].asText().split(".")).hasSize(3)
        assertThat(passwordHash).isNotEqualTo("password1234")
    }

    @Test
    fun `invalid password returns invalid credentials`() {
        adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "manager@climbdesk.local",
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = AdminUserRole.MANAGER,
                status = AdminUserStatus.ACTIVE,
            ),
        )

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"manager@climbdesk.local","password":"wrong-password"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
        }
    }

    @Test
    fun `inactive admin user returns admin user inactive`() {
        adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "manager@climbdesk.local",
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = AdminUserRole.MANAGER,
                status = AdminUserStatus.INACTIVE,
            ),
        )

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"manager@climbdesk.local","password":"password1234"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ADMIN_USER_INACTIVE") }
        }
    }

    @Test
    fun `inactive admin user with invalid password returns invalid credentials`() {
        adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "manager@climbdesk.local",
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = AdminUserRole.MANAGER,
                status = AdminUserStatus.INACTIVE,
            ),
        )

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"manager@climbdesk.local","password":"wrong-password"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
        }
    }

}
