package dev.climbdesk.auth.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUserRole
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class JwtAccessTokenVerifier(
    private val jwtProperties: JwtProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(jwtProperties.secret.isNotBlank()) { "JWT secret must be configured." }
    }

    fun verify(token: String): JwtAuthenticationPrincipal? {
        val parts = token.split(".")
        if (parts.size != TOKEN_PARTS) {
            return null
        }

        val signingInput = "${parts[0]}.${parts[1]}"
        if (!signatureMatches(signingInput, parts[2])) {
            return null
        }

        return runCatching {
            val payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]))
            val expiresAt = payload["exp"]?.asLong() ?: return null
            if (expiresAt <= clock.instant().epochSecond) {
                return null
            }

            JwtAuthenticationPrincipal(
                adminUserId = payload["sub"]?.asText()?.toLongOrNull() ?: return null,
                email = payload["email"]?.asText() ?: return null,
                role = AdminUserRole.valueOf(payload["role"]?.asText() ?: return null),
            )
        }.getOrNull()
    }

    private fun signatureMatches(signingInput: String, providedSignature: String): Boolean {
        val expectedSignature = sign(signingInput)
        return MessageDigest.isEqual(
            expectedSignature.toByteArray(Charsets.UTF_8),
            providedSignature.toByteArray(Charsets.UTF_8),
        )
    }

    private fun sign(signingInput: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(jwtProperties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(signingInput.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val TOKEN_PARTS = 3
    }
}
