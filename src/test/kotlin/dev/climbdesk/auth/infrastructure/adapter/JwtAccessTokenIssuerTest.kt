package dev.climbdesk.auth.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

class JwtAccessTokenIssuerTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `issues signed JWT access token with configured expiry`() {
        val issuer = JwtAccessTokenIssuer(
            jwtProperties = JwtProperties(
                secret = "test-secret-that-is-long-enough",
                expiresIn = 3600,
            ),
            objectMapper = objectMapper,
        )

        val issuedToken = issuer.issue(
            AdminUser(
                id = 1,
                email = "manager@climbdesk.local",
                passwordHash = "password-hash",
                role = AdminUserRole.MANAGER,
                status = AdminUserStatus.ACTIVE,
            ),
        )

        val tokenParts = issuedToken.token.split(".")
        val payload = objectMapper.readValue(
            Base64.getUrlDecoder().decode(tokenParts[1]),
            Map::class.java,
        )

        assertThat(tokenParts).hasSize(3)
        assertThat(issuedToken.expiresIn).isEqualTo(3600)
        assertThat(payload["sub"]).isEqualTo("1")
        assertThat(payload["email"]).isEqualTo("manager@climbdesk.local")
        assertThat(payload["role"]).isEqualTo("MANAGER")
        assertThat((payload["exp"] as Number).toLong()).isEqualTo((payload["iat"] as Number).toLong() + 3600)
    }
}
