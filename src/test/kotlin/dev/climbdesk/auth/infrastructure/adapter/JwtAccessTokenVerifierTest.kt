package dev.climbdesk.auth.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtAccessTokenVerifierTest {
    private val objectMapper = ObjectMapper()
    private val secret = "test-secret-that-is-long-enough"
    private val verifier = JwtAccessTokenVerifier(
        jwtProperties = JwtProperties(secret = secret),
        objectMapper = objectMapper,
        clock = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC),
    )

    @Test
    fun `verifies signed access token`() {
        val issuer = JwtAccessTokenIssuer(
            jwtProperties = JwtProperties(secret = secret, expiresIn = 3600),
            objectMapper = objectMapper,
        )

        val token = issuer.issue(
            AdminUser(
                id = 1,
                email = "manager@climbdesk.local",
                passwordHash = "password-hash",
                role = AdminUserRole.MANAGER,
                status = AdminUserStatus.ACTIVE,
            ),
        ).token

        val principal = verifier.verify(token)

        assertThat(principal).isEqualTo(
            JwtAuthenticationPrincipal(
                adminUserId = 1,
                email = "manager@climbdesk.local",
                role = AdminUserRole.MANAGER,
            ),
        )
    }

    @Test
    fun `rejects token with invalid signature`() {
        val token = signedToken(mapOf("sub" to "1", "email" to "manager@climbdesk.local", "role" to "MANAGER", "exp" to 2_000))
        val tamperedToken = "${token.dropLast(1)}x"

        assertThat(verifier.verify(tamperedToken)).isNull()
    }

    @Test
    fun `rejects expired token`() {
        val token = signedToken(mapOf("sub" to "1", "email" to "manager@climbdesk.local", "role" to "MANAGER", "exp" to 999))

        assertThat(verifier.verify(token)).isNull()
    }

    private fun signedToken(payload: Map<String, Any>): String {
        val header = mapOf("alg" to "HS256", "typ" to "JWT")
        val signingInput = "${encode(header)}.${encode(payload)}"
        return "$signingInput.${sign(signingInput)}"
    }

    private fun encode(value: Map<String, Any>): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(value))

    private fun sign(signingInput: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(signingInput.toByteArray(Charsets.UTF_8)))
    }
}
