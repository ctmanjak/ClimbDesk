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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
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
        "spring.jpa.hibernate.ddl-auto=create-drop",
    ],
)
@AutoConfigureMockMvc
class MemberQueryIntegrationTest @Autowired constructor(
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

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can list members`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        saveMember(name = "First Member", phone = "010-0000-0001")
        val second = saveMember(name = "Second Member", phone = "010-0000-0002")
        val third = saveMember(name = "Third Member", phone = "010-0000-0003")

        mockMvc.get("/api/v1/members") {
            param("page", "0")
            param("size", "2")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].id") { value(third.id) }
            jsonPath("$.items[0].name") { value("Third Member") }
            jsonPath("$.items[1].id") { value(second.id) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(2) }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.totalPages") { value(2) }
        }
    }

    @Test
    fun `member list uses default paging and latest first ordering`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val first = saveMember(name = "First Member", phone = "010-0000-0001")
        val second = saveMember(name = "Second Member", phone = "010-0000-0002")

        mockMvc.get("/api/v1/members") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].id") { value(second.id) }
            jsonPath("$.items[1].id") { value(first.id) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(20) }
            jsonPath("$.totalElements") { value(2) }
            jsonPath("$.totalPages") { value(1) }
        }
    }

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can retrieve member detail`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val member = saveMember(
            name = "Hong Gil Dong",
            phone = "010-1234-5678",
            email = "hong@example.com",
        )

        mockMvc.get("/api/v1/members/${member.id}") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(member.id) }
            jsonPath("$.name") { value("Hong Gil Dong") }
            jsonPath("$.phone") { value("010-1234-5678") }
            jsonPath("$.email") { value("hong@example.com") }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.createdAt") { isNotEmpty() }
            jsonPath("$.deactivatedAt") { value(null) }
        }
    }

    @Test
    fun `missing member returns member not found`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val nonExistingMemberId = Long.MAX_VALUE

        mockMvc.get("/api/v1/members/$nonExistingMemberId") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("MEMBER_NOT_FOUND") }
            jsonPath("$.message") { value("Member not found.") }
        }
    }

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can deactivate member`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val member = saveMember(name = "Hong Gil Dong", phone = "010-1234-5678")

        mockMvc.patch("/api/v1/members/${member.id}/deactivate") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(member.id) }
            jsonPath("$.status") { value("INACTIVE") }
            jsonPath("$.deactivatedAt") { isNotEmpty() }
        }

        val deactivated = memberJpaRepository.findById(member.id).orElseThrow()
        assertThat(deactivated.status).isEqualTo(MemberStatus.INACTIVE)
        assertThat(deactivated.deactivatedAt).isNotNull()
    }

    @Test
    fun `deactivate member is idempotent`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(
            name = "Hong Gil Dong",
            phone = "010-1234-5678",
            status = MemberStatus.INACTIVE,
            deactivatedAt = Instant.parse("2026-05-20T01:00:00Z"),
        )

        mockMvc.patch("/api/v1/members/${member.id}/deactivate") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("INACTIVE") }
            jsonPath("$.deactivatedAt") { value("2026-05-20T01:00:00Z") }
        }
    }

    @Test
    fun `deactivate missing member returns member not found`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val nonExistingMemberId = Long.MAX_VALUE

        mockMvc.patch("/api/v1/members/$nonExistingMemberId/deactivate") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("MEMBER_NOT_FOUND") }
            jsonPath("$.message") { value("Member not found.") }
        }
    }

    @Test
    fun `member list rejects invalid paging`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.get("/api/v1/members") {
            param("page", "-1")
            param("size", "20")
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }

        mockMvc.get("/api/v1/members") {
            param("page", "0")
            param("size", "101")
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `member list requires jwt authorization`() {
        mockMvc.get("/api/v1/members")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    @Test
    fun `member detail requires jwt authorization`() {
        val member = saveMember(name = "Hong Gil Dong", phone = "010-1234-5678")

        mockMvc.get("/api/v1/members/${member.id}")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    @Test
    fun `deactivate member requires jwt authorization`() {
        val member = saveMember(name = "Hong Gil Dong", phone = "010-1234-5678")

        mockMvc.patch("/api/v1/members/${member.id}/deactivate")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    private fun saveMember(
        name: String,
        phone: String,
        email: String? = null,
        status: MemberStatus = MemberStatus.ACTIVE,
        deactivatedAt: Instant? = null,
    ): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = name,
                phone = phone,
                email = email,
                status = status,
                deactivatedAt = deactivatedAt,
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
