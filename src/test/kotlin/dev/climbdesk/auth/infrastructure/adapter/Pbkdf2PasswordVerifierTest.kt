package dev.climbdesk.auth.infrastructure.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Pbkdf2PasswordVerifierTest {
    private val passwordVerifier = Pbkdf2PasswordVerifier()

    @Test
    fun `encoded password matches original raw password`() {
        val passwordHash = Pbkdf2PasswordVerifier.encode("password1234")

        assertThat(passwordHash).isNotEqualTo("password1234")
        assertThat(passwordVerifier.matches("password1234", passwordHash)).isTrue()
    }

    @Test
    fun `encoded password does not match different raw password`() {
        val passwordHash = Pbkdf2PasswordVerifier.encode("password1234")

        assertThat(passwordVerifier.matches("wrong-password", passwordHash)).isFalse()
    }

    @Test
    fun `malformed hash with invalid iterations fails closed`() {
        val passwordHash = Pbkdf2PasswordVerifier.encode("password1234")
        val parts = passwordHash.split("$").toMutableList()
        parts[1] = "not-a-number"
        val malformedHash = parts.joinToString("$")

        assertThat(passwordVerifier.matches("password1234", malformedHash)).isFalse()
    }

    @Test
    fun `malformed hash shape fails closed`() {
        val passwordHash = Pbkdf2PasswordVerifier.encode("password1234")
        val malformedHash = passwordHash.substringBeforeLast("$")

        assertThat(passwordVerifier.matches("password1234", malformedHash)).isFalse()
        assertThat(passwordVerifier.matches("password1234", "not-a-pbkdf2-hash")).isFalse()
    }
}
