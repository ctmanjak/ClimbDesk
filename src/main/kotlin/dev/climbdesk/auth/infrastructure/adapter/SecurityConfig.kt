package dev.climbdesk.auth.infrastructure.adapter

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    fun jwtAuthenticationFilter(jwtAccessTokenVerifier: JwtAccessTokenVerifier): JwtAuthenticationFilter =
        JwtAuthenticationFilter(jwtAccessTokenVerifier)

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
        jwtAuthenticationEntryPoint: JwtAuthenticationEntryPoint,
        jwtAccessDeniedHandler: JwtAccessDeniedHandler,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(jwtAuthenticationEntryPoint)
                it.accessDeniedHandler(jwtAccessDeniedHandler)
            }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                it.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
}
