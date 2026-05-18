package dev.climbdesk.auth.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.common.error.ErrorResponse
import dev.climbdesk.common.error.GlobalExceptionHandler
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

@Component
class SecurityErrorResponseWriter(
    private val objectMapper: ObjectMapper,
) {
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        errorCode: ErrorCode,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            ErrorResponse(
                timestamp = OffsetDateTime.now(),
                status = status.value(),
                code = errorCode.name,
                message = errorCode.defaultMessage,
                path = request.requestURI,
                traceId = traceId(request),
            ),
        )
    }

    private fun traceId(request: HttpServletRequest): String =
        request.getHeader(GlobalExceptionHandler.TRACE_ID_HEADER)
            ?: MDC.get(TRACE_ID_MDC_KEY)
            ?: UUID.randomUUID().toString()

    companion object {
        private const val TRACE_ID_MDC_KEY = "traceId"
    }
}
