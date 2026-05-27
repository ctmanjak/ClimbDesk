package dev.climbdesk.pass.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.auth.infrastructure.adapter.Pbkdf2PasswordVerifier
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaEntity
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassProductType
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaRepository
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
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
class MemberPassIssuanceIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
    private val memberPassJpaRepository: MemberPassJpaRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun setUp() {
        memberPassJpaRepository.deleteAll()
        passProductJpaRepository.deleteAll()
        memberJpaRepository.deleteAll()
        adminUserJpaRepository.deleteAll()
    }

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can issue member pass to active member`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val passProduct = savePassProduct(
            name = "10 Count Pass",
            totalCount = 10,
            price = BigDecimal("150000"),
            validDays = 90,
        )
        val expiresAt = "2026-08-01T14:59:59Z"

        val response = mockMvc.post("/api/v1/member-passes") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"memberId":${member.id},"passProductId":${passProduct.id},"expiresAt":"$expiresAt"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.memberId") { value(member.id) }
            jsonPath("$.passProductId") { value(passProduct.id) }
            jsonPath("$.productNameSnapshot") { value("10 Count Pass") }
            jsonPath("$.totalCount") { value(10) }
            jsonPath("$.remainingCount") { value(10) }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.issuedAt") { isNotEmpty() }
            jsonPath("$.expiresAt") { value(expiresAt) }
        }.andReturn().response.contentAsString

        val createdId = objectMapper.readTree(response)["id"].asLong()
        val created = memberPassJpaRepository.findById(createdId).orElseThrow()
        assertThat(created.productNameSnapshot).isEqualTo("10 Count Pass")
        assertThat(created.passTypeSnapshot).isEqualTo(PassProductType.COUNT_PASS)
        assertThat(created.totalCount).isEqualTo(10)
        assertThat(created.remainingCount).isEqualTo(10)
        assertThat(created.priceSnapshot).isEqualByComparingTo("150000")
        assertThat(created.validDaysSnapshot).isEqualTo(90)
        assertThat(created.status).isEqualTo(MemberPassStatus.ACTIVE)
        assertThat(created.version).isZero()
    }

    @Test
    fun `member pass issuance accepts nullable expires at`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val passProduct = savePassProduct(name = "Single Count Pass", totalCount = 1)

        mockMvc.post("/api/v1/member-passes") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"memberId":${member.id},"passProductId":${passProduct.id}}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.expiresAt") { value(null) }
        }
    }

    @Test
    fun `inactive member issuance fails with member inactive`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.INACTIVE, deactivatedAt = Instant.parse("2026-05-01T01:00:00Z"))
        val passProduct = savePassProduct(name = "10 Count Pass", totalCount = 10)

        mockMvc.post("/api/v1/member-passes") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"memberId":${member.id},"passProductId":${passProduct.id}}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("MEMBER_INACTIVE") }
            jsonPath("$.message") { value("Member is inactive.") }
        }

        assertThat(memberPassJpaRepository.count()).isZero()
    }

    @Test
    fun `missing member returns member not found`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val passProduct = savePassProduct(name = "10 Count Pass", totalCount = 10)

        mockMvc.post("/api/v1/member-passes") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"memberId":9223372036854775807,"passProductId":${passProduct.id}}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("MEMBER_NOT_FOUND") }
        }

        assertThat(memberPassJpaRepository.count()).isZero()
    }

    @Test
    fun `missing pass product returns pass product not found`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)

        mockMvc.post("/api/v1/member-passes") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"memberId":${member.id},"passProductId":9223372036854775807}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("PASS_PRODUCT_NOT_FOUND") }
        }

        assertThat(memberPassJpaRepository.count()).isZero()
    }

    @Test
    fun `member pass issuance rejects expires at before issued at`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val passProduct = savePassProduct(name = "10 Count Pass", totalCount = 10)

        mockMvc.post("/api/v1/member-passes") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"memberId":${member.id},"passProductId":${passProduct.id},"expiresAt":"2000-01-01T00:00:00Z"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }

        assertThat(memberPassJpaRepository.count()).isZero()
    }

    @Test
    fun `member pass issuance stores product snapshot independent from later product entity changes`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val member = saveMember(status = MemberStatus.ACTIVE)
        val passProduct = savePassProduct(
            name = "10 Count Pass",
            totalCount = 10,
            price = BigDecimal("150000"),
            validDays = 90,
        )

        val response = mockMvc.post("/api/v1/member-passes") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"memberId":${member.id},"passProductId":${passProduct.id}}"""
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString

        val createdId = objectMapper.readTree(response)["id"].asLong()
        jdbcTemplate.update(
            """
            update pass_products
            set name = 'Updated Pass', total_count = 20, price = 200000, valid_days = 120
            where id = ?
            """.trimIndent(),
            passProduct.id,
        )

        val savedSnapshot = memberPassJpaRepository.findById(createdId).orElseThrow()
        assertThat(savedSnapshot.productNameSnapshot).isEqualTo("10 Count Pass")
        assertThat(savedSnapshot.totalCount).isEqualTo(10)
        assertThat(savedSnapshot.priceSnapshot).isEqualByComparingTo("150000")
        assertThat(savedSnapshot.validDaysSnapshot).isEqualTo(90)
    }

    @Test
    fun `member pass issuance requires jwt authorization`() {
        val member = saveMember(status = MemberStatus.ACTIVE)
        val passProduct = savePassProduct(name = "10 Count Pass", totalCount = 10)

        mockMvc.post("/api/v1/member-passes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"memberId":${member.id},"passProductId":${passProduct.id}}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
    }

    private fun saveMember(
        status: MemberStatus,
        deactivatedAt: Instant? = null,
    ): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Hong Gil Dong",
                phone = "010-${System.nanoTime()}",
                email = null,
                status = status,
                deactivatedAt = deactivatedAt,
            ),
        )

    private fun savePassProduct(
        name: String,
        totalCount: Int,
        price: BigDecimal? = null,
        validDays: Int? = null,
    ): PassProductJpaEntity =
        passProductJpaRepository.saveAndFlush(
            PassProductJpaEntity(
                name = name,
                type = PassProductType.COUNT_PASS,
                totalCount = totalCount,
                price = price,
                validDays = validDays,
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
