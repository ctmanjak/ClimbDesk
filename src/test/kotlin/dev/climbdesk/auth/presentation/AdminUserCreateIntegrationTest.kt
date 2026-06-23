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
import org.springframework.test.web.servlet.patch
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
class AdminUserCreateIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val adminUserJpaRepository: AdminUserJpaRepository,
) {
    @BeforeEach
    fun setUp() {
        adminUserJpaRepository.deleteAll()
    }

    @Test
    fun `manager can create active admin user with hashed password`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        val response = mockMvc.post("/api/v1/admin-users") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"email":"staff@climbdesk.local","password":"password1234","role":"STAFF"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.email") { value("staff@climbdesk.local") }
            jsonPath("$.role") { value("STAFF") }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.createdAt") { isNotEmpty() }
            jsonPath("$.password") { doesNotExist() }
            jsonPath("$.passwordHash") { doesNotExist() }
        }.andReturn().response.contentAsString

        val createdId = objectMapper.readTree(response)["id"].asLong()
        val created = adminUserJpaRepository.findById(createdId).orElseThrow()
        assertThat(created.status).isEqualTo(AdminUserStatus.ACTIVE)
        assertThat(created.passwordHash).isNotEqualTo("password1234")
        assertThat(Pbkdf2PasswordVerifier().matches("password1234", created.passwordHash)).isTrue()
    }

    @Test
    fun `staff cannot create admin user`() {
        val staffToken = accessTokenFor("staff@climbdesk.local", AdminUserRole.STAFF)

        mockMvc.post("/api/v1/admin-users") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $staffToken")
            content = """{"email":"new-staff@climbdesk.local","password":"password1234","role":"STAFF"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }

        assertThat(adminUserJpaRepository.existsByEmail("new-staff@climbdesk.local")).isFalse()
    }

    @Test
    fun `manager can change admin user role`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val staff = adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "staff@climbdesk.local",
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = AdminUserRole.STAFF,
                status = AdminUserStatus.ACTIVE,
            ),
        )

        mockMvc.patch("/api/v1/admin-users/${staff.id}/role") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"role":"MANAGER"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(staff.id) }
            jsonPath("$.role") { value("MANAGER") }
            jsonPath("$.status") { value("ACTIVE") }
        }

        assertThat(adminUserJpaRepository.findById(staff.id).orElseThrow().role).isEqualTo(AdminUserRole.MANAGER)
    }

    @Test
    fun `staff cannot change admin user role`() {
        val staffToken = accessTokenFor("staff@climbdesk.local", AdminUserRole.STAFF)
        val target = adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "target@climbdesk.local",
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = AdminUserRole.STAFF,
                status = AdminUserStatus.ACTIVE,
            ),
        )

        mockMvc.patch("/api/v1/admin-users/${target.id}/role") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $staffToken")
            content = """{"role":"MANAGER"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }

        assertThat(adminUserJpaRepository.findById(target.id).orElseThrow().role)
            .isEqualTo(AdminUserRole.STAFF)
    }

    @Test
    fun `staff cannot activate admin user`() {
        val staffToken = accessTokenFor("staff@climbdesk.local", AdminUserRole.STAFF)
        val target = adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "target@climbdesk.local",
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = AdminUserRole.STAFF,
                status = AdminUserStatus.INACTIVE,
            ),
        )

        mockMvc.patch("/api/v1/admin-users/${target.id}/activate") {
            header("Authorization", "Bearer $staffToken")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.status") { value(403) }
            jsonPath("$.code") { value("FORBIDDEN") }
            jsonPath("$.path") { value("/api/v1/admin-users/${target.id}/activate") }
        }

        assertThat(adminUserJpaRepository.findById(target.id).orElseThrow().status)
            .isEqualTo(AdminUserStatus.INACTIVE)
    }

    @Test
    fun `staff cannot deactivate admin user`() {
        val staffToken = accessTokenFor("staff@climbdesk.local", AdminUserRole.STAFF)
        val target = adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "target@climbdesk.local",
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = AdminUserRole.STAFF,
                status = AdminUserStatus.ACTIVE,
            ),
        )

        mockMvc.patch("/api/v1/admin-users/${target.id}/deactivate") {
            header("Authorization", "Bearer $staffToken")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.status") { value(403) }
            jsonPath("$.code") { value("FORBIDDEN") }
            jsonPath("$.path") { value("/api/v1/admin-users/${target.id}/deactivate") }
        }

        assertThat(adminUserJpaRepository.findById(target.id).orElseThrow().status)
            .isEqualTo(AdminUserStatus.ACTIVE)
    }

    @Test
    fun `manager can activate and deactivate admin user`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val staff = adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "staff@climbdesk.local",
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = AdminUserRole.STAFF,
                status = AdminUserStatus.ACTIVE,
            ),
        )

        mockMvc.patch("/api/v1/admin-users/${staff.id}/deactivate") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("INACTIVE") }
        }

        assertThat(adminUserJpaRepository.findById(staff.id).orElseThrow().status)
            .isEqualTo(AdminUserStatus.INACTIVE)

        mockMvc.patch("/api/v1/admin-users/${staff.id}/activate") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACTIVE") }
        }

        assertThat(adminUserJpaRepository.findById(staff.id).orElseThrow().status)
            .isEqualTo(AdminUserStatus.ACTIVE)
    }

    @Test
    fun `last active manager cannot be demoted or deactivated`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val manager = adminUserJpaRepository.findByEmail("manager@climbdesk.local")!!

        mockMvc.patch("/api/v1/admin-users/${manager.id}/role") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"role":"STAFF"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("LAST_ACTIVE_MANAGER_REQUIRED") }
        }

        mockMvc.patch("/api/v1/admin-users/${manager.id}/deactivate") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("LAST_ACTIVE_MANAGER_REQUIRED") }
        }

        val unchanged = adminUserJpaRepository.findById(manager.id).orElseThrow()
        assertThat(unchanged.role).isEqualTo(AdminUserRole.MANAGER)
        assertThat(unchanged.status).isEqualTo(AdminUserStatus.ACTIVE)
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
}
