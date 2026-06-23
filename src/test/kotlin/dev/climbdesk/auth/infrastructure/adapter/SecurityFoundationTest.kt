package dev.climbdesk.auth.infrastructure.adapter

import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(
    controllers = [
        SecurityFoundationTest.SecurityLoginTestController::class,
        SecurityFoundationTest.SecurityProtectedTestController::class,
    ],
)
@Import(
    SecurityConfig::class,
    JwtAuthenticationFilter::class,
    JwtAccessTokenVerifier::class,
    JwtAccessTokenIssuer::class,
    JwtAuthenticationEntryPoint::class,
    JwtAccessDeniedHandler::class,
    SecurityErrorResponseWriter::class,
    SecurityFoundationTest.TestControllersConfig::class,
)
@EnableConfigurationProperties(JwtProperties::class)
@TestPropertySource(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough",
        "climbdesk.auth.jwt.expires-in=3600",
    ],
)
class SecurityFoundationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtAccessTokenIssuer: JwtAccessTokenIssuer,
) {
    @Test
    fun `login endpoint is public`() {
        mockMvc.post("/api/v1/auth/login")
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `protected endpoint without bearer token returns unauthorized error response`() {
        mockMvc.get("/api/v1/security-test/protected") {
            header("X-Trace-Id", "trace-auth-required")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.timestamp") { value(matchesPattern(UTC_TIMESTAMP_PATTERN)) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.code") { value("UNAUTHORIZED") }
            jsonPath("$.path") { value("/api/v1/security-test/protected") }
            jsonPath("$.traceId") { value("trace-auth-required") }
        }
    }

    @Test
    fun `protected endpoint with invalid bearer token returns unauthorized error response`() {
        mockMvc.get("/api/v1/security-test/protected") {
            header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
            header("X-Trace-Id", "trace-invalid-token")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.timestamp") { value(matchesPattern(UTC_TIMESTAMP_PATTERN)) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.code") { value("UNAUTHORIZED") }
            jsonPath("$.path") { value("/api/v1/security-test/protected") }
            jsonPath("$.traceId") { value("trace-invalid-token") }
        }
    }

    @Test
    fun `protected endpoint with valid bearer token is authenticated`() {
        mockMvc.get("/api/v1/security-test/protected") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(AdminUserRole.STAFF)}")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `manager endpoint with staff token returns forbidden error response`() {
        mockMvc.get("/api/v1/security-test/manager") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(AdminUserRole.STAFF)}")
            header("X-Trace-Id", "trace-forbidden")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.timestamp") { value(matchesPattern(UTC_TIMESTAMP_PATTERN)) }
            jsonPath("$.status") { value(403) }
            jsonPath("$.code") { value("FORBIDDEN") }
            jsonPath("$.path") { value("/api/v1/security-test/manager") }
            jsonPath("$.traceId") { value("trace-forbidden") }
        }
    }

    private fun accessToken(role: AdminUserRole): String =
        jwtAccessTokenIssuer.issue(
            AdminUser(
                id = 1,
                email = "admin@climbdesk.local",
                passwordHash = "password-hash",
                role = role,
                status = AdminUserStatus.ACTIVE,
            ),
        ).token

    @TestConfiguration
    class TestControllersConfig {
        @Bean
        fun securityLoginTestController(): SecurityLoginTestController =
            SecurityLoginTestController()

        @Bean
        fun securityProtectedTestController(): SecurityProtectedTestController =
            SecurityProtectedTestController()
    }

    @RestController
    class SecurityLoginTestController {
        @PostMapping("/api/v1/auth/login")
        fun login(): String = "ok"
    }

    @RestController
    @RequestMapping("/api/v1/security-test")
    class SecurityProtectedTestController {
        @GetMapping("/protected")
        fun protected(): String = "ok"

        @PreAuthorize("hasRole('MANAGER')")
        @GetMapping("/manager")
        fun manager(): String = "ok"
    }
}

private const val UTC_TIMESTAMP_PATTERN =
    """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z"""
