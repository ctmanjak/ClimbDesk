package dev.climbdesk.auth.infrastructure.adapter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val jwtAccessTokenVerifier: JwtAccessTokenVerifier,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = bearerToken(request)
        if (token != null) {
            val principal = jwtAccessTokenVerifier.verify(token)
            if (principal != null) {
                SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_${principal.role.name}")),
                )
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun bearerToken(request: HttpServletRequest): String? {
        val authorizationHeader = request.getHeader(AUTHORIZATION_HEADER) ?: return null
        if (!authorizationHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            return null
        }
        return authorizationHeader.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotBlank() }
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}
