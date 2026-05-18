package dev.climbdesk.auth.presentation

import dev.climbdesk.auth.application.AuthApplicationService
import dev.climbdesk.auth.application.CreateAdminUserCommand
import dev.climbdesk.auth.application.CreateAdminUserResult
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.common.error.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(controllers = [AdminUserController::class])
@AutoConfigureMockMvc(addFilters = false)
class AdminUserControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var authApplicationService: AuthApplicationService

    @Test
    fun `create admin user returns created admin user without password fields`() {
        doReturn(
            CreateAdminUserResult(
                id = 2,
                email = "staff@climbdesk.local",
                role = AdminUserRole.STAFF,
                status = AdminUserStatus.ACTIVE,
                createdAt = Instant.parse("2026-05-01T01:00:00Z"),
            ),
        ).`when`(authApplicationService).createAdminUser(
            CreateAdminUserCommand("staff@climbdesk.local", "password1234", AdminUserRole.STAFF),
        )

        mockMvc.post("/api/v1/admin-users") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"staff@climbdesk.local","password":"password1234","role":"STAFF"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(2) }
            jsonPath("$.email") { value("staff@climbdesk.local") }
            jsonPath("$.role") { value("STAFF") }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.createdAt") { value("2026-05-01T01:00:00Z") }
            jsonPath("$.password") { doesNotExist() }
            jsonPath("$.passwordHash") { doesNotExist() }
        }
    }

    @Test
    fun `duplicate email returns documented error response`() {
        doThrow(ApplicationException(ErrorCode.DUPLICATE_ADMIN_USER_EMAIL))
            .`when`(authApplicationService).createAdminUser(
                CreateAdminUserCommand("staff@climbdesk.local", "password1234", AdminUserRole.STAFF),
            )

        mockMvc.post("/api/v1/admin-users") {
            contentType = MediaType.APPLICATION_JSON
            header(GlobalExceptionHandler.TRACE_ID_HEADER, "trace-duplicate")
            content = """{"email":"staff@climbdesk.local","password":"password1234","role":"STAFF"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.status") { value(409) }
            jsonPath("$.code") { value("DUPLICATE_ADMIN_USER_EMAIL") }
            jsonPath("$.path") { value("/api/v1/admin-users") }
            jsonPath("$.traceId") { value("trace-duplicate") }
        }
    }

    @Test
    fun `invalid request returns validation failed`() {
        mockMvc.post("/api/v1/admin-users") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"","password":"","role":null}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }
}
