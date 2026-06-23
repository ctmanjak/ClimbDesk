package dev.climbdesk.member.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.auth.infrastructure.adapter.Pbkdf2PasswordVerifier
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaEntity
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
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
class MemberCreateIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
) {
    @BeforeEach
    fun setUp() {
        memberJpaRepository.deleteAll()
        adminUserJpaRepository.deleteAll()
    }

    @Test
    fun `manager can create active member`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        val response = mockMvc.post("/api/v1/members") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"Hong Gil Dong","phone":"010-1234-5678","email":"hong@example.com"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.name") { value("Hong Gil Dong") }
            jsonPath("$.phone") { value("010-1234-5678") }
            jsonPath("$.email") { value("hong@example.com") }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.createdAt") { isNotEmpty() }
            jsonPath("$.deactivatedAt") { value(null) }
        }.andReturn().response.contentAsString

        val createdId = objectMapper.readTree(response)["id"].asLong()
        val created = memberJpaRepository.findById(createdId).orElseThrow()
        assertThat(created.status).isEqualTo(MemberStatus.ACTIVE)
        assertThat(created.deactivatedAt).isNull()
    }

    @Test
    fun `staff can create member without email`() {
        val staffToken = accessTokenFor("staff@climbdesk.local", AdminUserRole.STAFF)

        val response = mockMvc.post("/api/v1/members") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $staffToken")
            content = """{"name":"Hong Gil Dong","phone":"010-1234-5678"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.email") { value(null) }
            jsonPath("$.status") { value("ACTIVE") }
        }.andReturn().response.contentAsString

        val createdId = objectMapper.readTree(response)["id"].asLong()
        assertThat(memberJpaRepository.findById(createdId).orElseThrow().email).isNull()
    }

    @Test
    fun `duplicate phone fails with explicit error`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Existing Member",
                phone = "010-1234-5678",
                email = null,
                status = MemberStatus.ACTIVE,
            ),
        )

        mockMvc.post("/api/v1/members") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"Hong Gil Dong","phone":"010-1234-5678"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DUPLICATE_MEMBER_PHONE") }
            jsonPath("$.message") { value("Member phone already exists.") }
        }

        assertThat(memberJpaRepository.count()).isEqualTo(1)
    }

    @Test
    fun `invalid request fails validation`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/members") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"","phone":""}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
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
}
