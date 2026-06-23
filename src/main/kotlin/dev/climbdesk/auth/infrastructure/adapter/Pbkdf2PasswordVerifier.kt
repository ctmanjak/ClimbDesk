package dev.climbdesk.auth.infrastructure.adapter

import dev.climbdesk.auth.application.PasswordVerifier
import dev.climbdesk.auth.application.PasswordHasher
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Component
class Pbkdf2PasswordVerifier : PasswordVerifier, PasswordHasher {
    override fun hash(rawPassword: String): String = encode(rawPassword)

    override fun matches(rawPassword: String, passwordHash: String): Boolean {
        val parsedHash = Pbkdf2PasswordHash.parse(passwordHash) ?: return false
        val actualHash = hash(rawPassword, parsedHash.salt, parsedHash.iterations, parsedHash.hash.size)
        return MessageDigest.isEqual(actualHash, parsedHash.hash)
    }

    companion object {
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KEY_LENGTH_BITS = 256
        private const val DEFAULT_ITERATIONS = 210_000
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32

        fun encode(rawPassword: String): String {
            val salt = ByteArray(SALT_BYTES)
            SecureRandom().nextBytes(salt)
            val hash = hash(rawPassword, salt, DEFAULT_ITERATIONS, HASH_BYTES)
            return Pbkdf2PasswordHash(DEFAULT_ITERATIONS, salt, hash).format()
        }

        private fun hash(
            rawPassword: String,
            salt: ByteArray,
            iterations: Int,
            hashBytes: Int,
        ): ByteArray {
            val spec = PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded.copyOf(hashBytes)
        }
    }
}

private data class Pbkdf2PasswordHash(
    val iterations: Int,
    val salt: ByteArray,
    val hash: ByteArray,
) {
    fun format(): String =
        listOf(
            ID,
            iterations.toString(),
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(hash),
        ).joinToString("$")

    companion object {
        private const val ID = "pbkdf2_sha256"

        fun parse(value: String): Pbkdf2PasswordHash? {
            val parts = value.split("$")
            if (parts.size != 4 || parts[0] != ID) {
                return null
            }

            return runCatching {
                Pbkdf2PasswordHash(
                    iterations = parts[1].toInt(),
                    salt = Base64.getDecoder().decode(parts[2]),
                    hash = Base64.getDecoder().decode(parts[3]),
                )
            }.getOrNull()
        }
    }
}
