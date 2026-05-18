package dev.climbdesk.auth.infrastructure.adapter

import dev.climbdesk.common.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

@Component
class JwtAccessDeniedHandler(
    private val securityErrorResponseWriter: SecurityErrorResponseWriter,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        securityErrorResponseWriter.write(
            request = request,
            response = response,
            status = HttpStatus.FORBIDDEN,
            errorCode = ErrorCode.FORBIDDEN,
        )
    }
}
