package dev.climbdesk.common.error

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@ExtendWith(SpringExtension::class)
@WebMvcTest(controllers = [ErrorHandlingTestController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class GlobalExceptionHandlerTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @Test
    fun `domain exception returns api spec error response`() {
        mockMvc.get("/test-errors/domain") {
            header(GlobalExceptionHandler.TRACE_ID_HEADER, "trace-domain")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.timestamp") { exists() }
            jsonPath("$.status") { value(409) }
            jsonPath("$.code") { value("DUPLICATE_RESERVATION") }
            jsonPath("$.message") {
                value("Member already has a confirmed reservation for this class session.")
            }
            jsonPath("$.path") { value("/test-errors/domain") }
            jsonPath("$.traceId") { value("trace-domain") }
            jsonPath("$.details") { doesNotExist() }
            jsonPath("$.stackTrace") { doesNotExist() }
        }
    }

    @Test
    fun `application exception maps error code to documented status`() {
        mockMvc.get("/test-errors/application") {
            header(GlobalExceptionHandler.TRACE_ID_HEADER, "trace-application")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.status") { value(404) }
            jsonPath("$.code") { value("MEMBER_NOT_FOUND") }
            jsonPath("$.message") { value("Member not found.") }
            jsonPath("$.traceId") { value("trace-application") }
        }
    }

    @Test
    fun `validation error returns details`() {
        mockMvc.post("/test-errors/validation") {
            contentType = MediaType.APPLICATION_JSON
            header(GlobalExceptionHandler.TRACE_ID_HEADER, "trace-validation")
            content = """{"phone":""}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.status") { value(400) }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.message") { value("Request validation failed.") }
            jsonPath("$.path") { value("/test-errors/validation") }
            jsonPath("$.traceId") { value("trace-validation") }
            jsonPath("$.details[0].field") { value("phone") }
            jsonPath("$.details[0].reason") { value("must not be blank") }
        }
    }

    @Test
    fun `unexpected exception does not leak internal details`() {
        mockMvc.get("/test-errors/unexpected").andExpect {
            status { isInternalServerError() }
            jsonPath("$.status") { value(500) }
            jsonPath("$.code") { value("INTERNAL_SERVER_ERROR") }
            jsonPath("$.message") { value("Internal server error.") }
            jsonPath("$.traceId") { value(matchesPattern(".+")) }
            jsonPath("$.exception") { doesNotExist() }
            jsonPath("$.trace") { doesNotExist() }
            jsonPath("$.stackTrace") { doesNotExist() }
            jsonPath("$.details") { doesNotExist() }
        }
    }
}

@RestController
@RequestMapping("/test-errors")
private class ErrorHandlingTestController {
    @GetMapping("/domain")
    fun domainError(): Nothing =
        throw DomainException(ErrorCode.DUPLICATE_RESERVATION)

    @GetMapping("/application")
    fun applicationError(): Nothing =
        throw ApplicationException(ErrorCode.MEMBER_NOT_FOUND)

    @PostMapping("/validation")
    fun validation(
        @Valid @RequestBody request: ValidationTestRequest,
    ): ValidationTestRequest = request

    @GetMapping("/unexpected")
    fun unexpected(): Nothing =
        throw IllegalStateException("database password leaked")
}

private data class ValidationTestRequest(
    @field:NotBlank
    val phone: String,
)
