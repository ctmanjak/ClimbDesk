package dev.climbdesk.auth.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.application.AccessTokenIssuer
import dev.climbdesk.auth.application.IssuedAccessToken
import dev.climbdesk.auth.domain.AdminUser
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class JwtAccessTokenIssuer(
    private val jwtProperties: JwtProperties,
    private val objectMapper: ObjectMapper,
) : AccessTokenIssuer {
    init {
        require(jwtProperties.secret.isNotBlank()) { "JWT secret must be configured." }
        require(jwtProperties.expiresIn > 0) { "JWT expiry must be positive." }
    }

    override fun issue(adminUser: AdminUser): IssuedAccessToken {
        val now = Instant.now().epochSecond
        val header = mapOf(
            "alg" to "HS256",
            "typ" to "JWT",
        )
        val payload = mapOf(
            "sub" to adminUser.id.toString(),
            "email" to adminUser.email,
            "role" to adminUser.role.name,
            "iat" to now,
            "exp" to now + jwtProperties.expiresIn,
        )
        val signingInput = "${encodeJson(header)}.${encodeJson(payload)}"
        val signature = sign(signingInput)
        return IssuedAccessToken(
            token = "$signingInput.$signature",
            expiresIn = jwtProperties.expiresIn,
        )
    }

    private fun encodeJson(value: Map<String, Any>): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(value))

    private fun sign(signingInput: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(jwtProperties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(signingInput.toByteArray(Charsets.UTF_8)))
    }
}
