package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.AdminUserResult
import dev.climbdesk.auth.application.AuthApplicationService
import dev.climbdesk.auth.application.LoginCommand
import dev.climbdesk.auth.application.LoginResult
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.common.error.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(controllers = [AuthController::class])
class AuthControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var authApplicationService: AuthApplicationService

    @Test
    fun `login returns access token and admin user`() {
        doReturn(
            LoginResult(
                accessToken = "access-token",
                tokenType = "Bearer",
                expiresIn = 3600,
                adminUser = AdminUserResult(
                    id = 1,
                    email = "manager@climbdesk.local",
                    role = AdminUserRole.MANAGER,
                    status = AdminUserStatus.ACTIVE,
                ),
            ),
        ).`when`(authApplicationService).login(
            LoginCommand("manager@climbdesk.local", "password1234"),
        )

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            header(GlobalExceptionHandler.TRACE_ID_HEADER, "trace-login")
            content = """{"email":"manager@climbdesk.local","password":"password1234"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { value("access-token") }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresIn") { value(3600) }
            jsonPath("$.adminUser.id") { value(1) }
            jsonPath("$.adminUser.email") { value("manager@climbdesk.local") }
            jsonPath("$.adminUser.role") { value("MANAGER") }
            jsonPath("$.adminUser.status") { value("ACTIVE") }
        }
    }

    @Test
    fun `invalid credentials returns documented error response`() {
        doThrow(ApplicationException(ErrorCode.INVALID_CREDENTIALS))
            .`when`(authApplicationService).login(
                LoginCommand("manager@climbdesk.local", "wrong-password"),
            )

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            header(GlobalExceptionHandler.TRACE_ID_HEADER, "trace-invalid")
            content = """{"email":"manager@climbdesk.local","password":"wrong-password"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.status") { value(401) }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
            jsonPath("$.message") { value("Invalid credentials.") }
            jsonPath("$.path") { value("/api/v1/auth/login") }
            jsonPath("$.traceId") { value("trace-invalid") }
        }
    }

    @Test
    fun `inactive admin user returns documented error response`() {
        doThrow(ApplicationException(ErrorCode.ADMIN_USER_INACTIVE))
            .`when`(authApplicationService).login(
                LoginCommand("manager@climbdesk.local", "password1234"),
            )

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            header(GlobalExceptionHandler.TRACE_ID_HEADER, "trace-inactive")
            content = """{"email":"manager@climbdesk.local","password":"password1234"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.status") { value(403) }
            jsonPath("$.code") { value("ADMIN_USER_INACTIVE") }
            jsonPath("$.message") { value("Admin user is inactive.") }
            jsonPath("$.path") { value("/api/v1/auth/login") }
            jsonPath("$.traceId") { value("trace-inactive") }
        }
    }
}
