package dev.climbdesk.auth.infrastructure.adapter

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "climbdesk.auth.jwt")
data class JwtProperties(
    val secret: String = "",
    val expiresIn: Long = 3600,
)
