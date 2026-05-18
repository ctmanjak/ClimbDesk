package dev.climbdesk.auth.infrastructure.adapter

import dev.climbdesk.common.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

@Component
class JwtAuthenticationEntryPoint(
    private val securityErrorResponseWriter: SecurityErrorResponseWriter,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        securityErrorResponseWriter.write(
            request = request,
            response = response,
            status = HttpStatus.UNAUTHORIZED,
            errorCode = ErrorCode.UNAUTHORIZED,
        )
    }
}
