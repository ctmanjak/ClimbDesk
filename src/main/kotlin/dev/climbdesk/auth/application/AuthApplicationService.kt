package dev.climbdesk.auth.application

import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRepository
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthApplicationService(
    private val adminUserRepository: AdminUserRepository,
    private val passwordVerifier: PasswordVerifier,
    private val passwordHasher: PasswordHasher,
    private val accessTokenIssuer: AccessTokenIssuer,
) {
    @Transactional
    fun createAdminUser(command: CreateAdminUserCommand): CreateAdminUserResult {
        if (adminUserRepository.existsByEmail(command.email)) {
            throw ApplicationException(ErrorCode.DUPLICATE_ADMIN_USER_EMAIL)
        }

        val adminUser = AdminUser.create(
            email = command.email,
            passwordHash = passwordHasher.hash(command.password),
            role = command.role,
        )

        return CreateAdminUserResult.from(adminUserRepository.save(adminUser))
    }

    @Transactional(readOnly = true)
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
