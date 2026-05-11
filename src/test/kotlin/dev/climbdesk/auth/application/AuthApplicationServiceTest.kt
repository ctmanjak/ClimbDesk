package dev.climbdesk.auth.application

import dev.climbdesk.auth.domain.AdminUser
import dev.climbdesk.auth.domain.AdminUserRepository
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuthApplicationServiceTest {
    @Test
    fun `active admin user can log in with valid credentials`() {
        val adminUser = adminUser(status = AdminUserStatus.ACTIVE)
        val service = authService(
            adminUser = adminUser,
            passwordMatches = true,
            issuedToken = IssuedAccessToken("access-token", 3600),
        )

        val result = service.login(LoginCommand("manager@climbdesk.local", "password1234"))

        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(result.tokenType).isEqualTo("Bearer")
        assertThat(result.expiresIn).isEqualTo(3600)
        assertThat(result.adminUser.id).isEqualTo(1)
        assertThat(result.adminUser.email).isEqualTo("manager@climbdesk.local")
        assertThat(result.adminUser.role).isEqualTo(AdminUserRole.MANAGER)
        assertThat(result.adminUser.status).isEqualTo(AdminUserStatus.ACTIVE)
    }

    @Test
    fun `invalid password fails with invalid credentials`() {
        val service = authService(
            adminUser = adminUser(status = AdminUserStatus.ACTIVE),
            passwordMatches = false,
        )

        assertThatThrownBy {
            service.login(LoginCommand("manager@climbdesk.local", "wrong-password"))
        }.isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_CREDENTIALS)
    }

    @Test
    fun `inactive admin user cannot receive token`() {
        val tokenIssuer = RecordingAccessTokenIssuer()
        val service = authService(
            adminUser = adminUser(status = AdminUserStatus.INACTIVE),
            passwordMatches = true,
            tokenIssuer = tokenIssuer,
        )

        assertThatThrownBy {
            service.login(LoginCommand("manager@climbdesk.local", "password1234"))
        }.isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.ADMIN_USER_INACTIVE)
        assertThat(tokenIssuer.issueCount).isZero()
    }

    private fun authService(
        adminUser: AdminUser?,
        passwordMatches: Boolean,
        issuedToken: IssuedAccessToken = IssuedAccessToken("unused-token", 3600),
        tokenIssuer: AccessTokenIssuer = RecordingAccessTokenIssuer(issuedToken),
    ): AuthApplicationService =
        AuthApplicationService(
            adminUserRepository = StaticAdminUserRepository(adminUser),
            passwordVerifier = StaticPasswordVerifier(passwordMatches),
            accessTokenIssuer = tokenIssuer,
        )

    private fun adminUser(status: AdminUserStatus): AdminUser =
        AdminUser(
            id = 1,
            email = "manager@climbdesk.local",
            passwordHash = "hashed-password",
            role = AdminUserRole.MANAGER,
            status = status,
        )
}

private class StaticAdminUserRepository(
    private val adminUser: AdminUser?,
) : AdminUserRepository {
    override fun findByEmail(email: String): AdminUser? = adminUser
}

private class StaticPasswordVerifier(
    private val matches: Boolean,
) : PasswordVerifier {
    override fun matches(rawPassword: String, passwordHash: String): Boolean = matches
}

private class RecordingAccessTokenIssuer(
    private val issuedToken: IssuedAccessToken = IssuedAccessToken("access-token", 3600),
) : AccessTokenIssuer {
    var issueCount: Int = 0
        private set

    override fun issue(adminUser: AdminUser): IssuedAccessToken {
        issueCount += 1
        return issuedToken
    }
}
