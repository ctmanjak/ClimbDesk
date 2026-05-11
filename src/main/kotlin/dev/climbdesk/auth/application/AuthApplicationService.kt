package dev.climbdesk.auth.application

import dev.climbdesk.auth.domain.AdminUserRepository
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import org.springframework.stereotype.Service

@Service
class AuthApplicationService(
    private val adminUserRepository: AdminUserRepository,
    private val passwordVerifier: PasswordVerifier,
    private val accessTokenIssuer: AccessTokenIssuer,
) {
    fun login(command: LoginCommand): LoginResult {
        val adminUser = adminUserRepository.findByEmail(command.email)
            ?: throw ApplicationException(ErrorCode.INVALID_CREDENTIALS)

        // Verify password first to ensure constant-time behavior
        if (!passwordVerifier.matches(command.password, adminUser.passwordHash)) {
            throw ApplicationException(ErrorCode.INVALID_CREDENTIALS)
        }

        if (!adminUser.isActive()) {
            throw ApplicationException(ErrorCode.ADMIN_USER_INACTIVE)
        }

        val accessToken = accessTokenIssuer.issue(adminUser)
        return LoginResult(
            accessToken = accessToken.token,
            tokenType = TOKEN_TYPE,
            expiresIn = accessToken.expiresIn,
            adminUser = AdminUserResult.from(adminUser),
        )
    }

    companion object {
        private const val TOKEN_TYPE = "Bearer"
    }
}
