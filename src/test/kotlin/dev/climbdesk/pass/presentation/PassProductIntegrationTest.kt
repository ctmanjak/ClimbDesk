package dev.climbdesk.pass.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.auth.infrastructure.adapter.Pbkdf2PasswordVerifier
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaEntity
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
import dev.climbdesk.pass.domain.PassProductType
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
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
class PassProductIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
) {
    @BeforeEach
    fun setUp() {
        passProductJpaRepository.deleteAll()
        adminUserJpaRepository.deleteAll()
    }

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can create count pass product`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)

        val response = mockMvc.post("/api/v1/pass-products") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $token")
            content = """{"name":"10 Count Pass","totalCount":10,"price":150000,"validDays":90}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.name") { value("10 Count Pass") }
            jsonPath("$.type") { value("COUNT_PASS") }
            jsonPath("$.totalCount") { value(10) }
            jsonPath("$.price") { value(150000) }
            jsonPath("$.validDays") { value(90) }
            jsonPath("$.createdAt") { isNotEmpty() }
        }.andReturn().response.contentAsString

        val createdId = objectMapper.readTree(response)["id"].asLong()
        val created = passProductJpaRepository.findById(createdId).orElseThrow()
        assertThat(created.type).isEqualTo(PassProductType.COUNT_PASS)
        assertThat(created.totalCount).isEqualTo(10)
    }

    @Test
    fun `pass product create accepts optional price and valid days`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/pass-products") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"Single Count Pass","totalCount":1}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.price") { value(null) }
            jsonPath("$.validDays") { value(null) }
            jsonPath("$.type") { value("COUNT_PASS") }
        }
    }

    @Test
    fun `pass product create rejects zero total count`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/pass-products") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"Invalid Pass","totalCount":0,"price":0,"validDays":1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `pass product create rejects negative total count`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/pass-products") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"Invalid Pass","totalCount":-1,"price":0,"validDays":1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `pass product create rejects negative price`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/pass-products") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"Invalid Pass","totalCount":1,"price":-1,"validDays":1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `pass product create rejects decimal price`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/pass-products") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"Invalid Pass","totalCount":1,"price":150000.5,"validDays":1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `pass product create rejects price over twelve digits`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/pass-products") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"Invalid Pass","totalCount":1,"price":1000000000000,"validDays":1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `pass product create rejects non-positive valid days`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.post("/api/v1/pass-products") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $managerToken")
            content = """{"name":"Invalid Pass","totalCount":1,"price":0,"validDays":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `pass product list uses default paging and latest first ordering`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val newer = savePassProduct(
            name = "Newer Pass",
            totalCount = 5,
            createdAt = Instant.parse("2026-05-02T01:00:00Z"),
        )
        val older = savePassProduct(
            name = "Older Pass",
            totalCount = 10,
            createdAt = Instant.parse("2026-05-01T01:00:00Z"),
        )

        mockMvc.get("/api/v1/pass-products") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].id") { value(newer.id) }
            jsonPath("$.items[1].id") { value(older.id) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(20) }
            jsonPath("$.totalElements") { value(2) }
            jsonPath("$.totalPages") { value(1) }
        }
    }

    @Test
    fun `pass product list uses descending id as tie breaker for equal created at`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val createdAt = Instant.parse("2026-05-01T01:00:00Z")
        val first = savePassProduct(name = "First Pass", totalCount = 5, createdAt = createdAt)
        val second = savePassProduct(name = "Second Pass", totalCount = 10, createdAt = createdAt)

        mockMvc.get("/api/v1/pass-products") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(second.id) }
            jsonPath("$.items[1].id") { value(first.id) }
        }
    }

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can list pass products`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        savePassProduct(name = "First Pass", totalCount = 5)
        val second = savePassProduct(name = "Second Pass", totalCount = 10)
        val third = savePassProduct(name = "Third Pass", totalCount = 20)

        mockMvc.get("/api/v1/pass-products") {
            param("page", "0")
            param("size", "2")
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].id") { value(third.id) }
            jsonPath("$.items[0].name") { value("Third Pass") }
            jsonPath("$.items[1].id") { value(second.id) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(2) }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.totalPages") { value(2) }
        }
    }

    @Test
    fun `pass product list rejects invalid paging`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)

        mockMvc.get("/api/v1/pass-products") {
            param("page", "-1")
            param("size", "20")
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }

        mockMvc.get("/api/v1/pass-products") {
            param("page", "0")
            param("size", "101")
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @ParameterizedTest
    @EnumSource(value = AdminUserRole::class, names = ["MANAGER", "STAFF"])
    fun `manager and staff can retrieve pass product detail`(role: AdminUserRole) {
        val token = accessTokenFor("${role.name.lowercase()}@climbdesk.local", role)
        val passProduct = savePassProduct(
            name = "10 Count Pass",
            totalCount = 10,
            price = BigDecimal("150000"),
            validDays = 90,
        )

        mockMvc.get("/api/v1/pass-products/${passProduct.id}") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(passProduct.id) }
            jsonPath("$.name") { value("10 Count Pass") }
            jsonPath("$.type") { value("COUNT_PASS") }
            jsonPath("$.totalCount") { value(10) }
            jsonPath("$.price") { value(150000) }
            jsonPath("$.validDays") { value(90) }
            jsonPath("$.createdAt") { isNotEmpty() }
        }
    }

    @Test
    fun `missing pass product returns pass product not found`() {
        val managerToken = accessTokenFor("manager@climbdesk.local", AdminUserRole.MANAGER)
        val nonExistingPassProductId = Long.MAX_VALUE

        mockMvc.get("/api/v1/pass-products/$nonExistingPassProductId") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("PASS_PRODUCT_NOT_FOUND") }
            jsonPath("$.message") { value("Pass product not found.") }
        }
    }

    @Test
    fun `pass product APIs require jwt authorization`() {
        val passProduct = savePassProduct(name = "10 Count Pass", totalCount = 10)

        mockMvc.post("/api/v1/pass-products") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"10 Count Pass","totalCount":10}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }

        mockMvc.get("/api/v1/pass-products")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }

        mockMvc.get("/api/v1/pass-products/${passProduct.id}")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    private fun savePassProduct(
        name: String,
        totalCount: Int,
        price: BigDecimal? = null,
        validDays: Int? = null,
        createdAt: Instant? = null,
    ): PassProductJpaEntity {
        val passProduct = passProductJpaRepository.saveAndFlush(
            PassProductJpaEntity(
                name = name,
                type = PassProductType.COUNT_PASS,
                totalCount = totalCount,
                price = price,
                validDays = validDays,
            ),
        )
        if (createdAt != null) {
            passProduct.createdAt = createdAt
            return passProductJpaRepository.saveAndFlush(passProduct)
        }
        return passProduct
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
