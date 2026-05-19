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

    @Test
    fun `create admin user stores hashed password and defaults to active`() {
        val repository = RecordingAdminUserRepository()
        val service = authService(
            adminUser = null,
            passwordMatches = false,
            repository = repository,
            passwordHasher = StaticPasswordHasher("hashed-password"),
        )

        val result = service.createAdminUser(
            CreateAdminUserCommand(
                email = "staff@climbdesk.local",
                password = "password1234",
                role = AdminUserRole.STAFF,
            ),
        )

        assertThat(result.id).isEqualTo(10)
        assertThat(result.email).isEqualTo("staff@climbdesk.local")
        assertThat(result.role).isEqualTo(AdminUserRole.STAFF)
        assertThat(result.status).isEqualTo(AdminUserStatus.ACTIVE)
        assertThat(repository.savedAdminUser?.passwordHash).isEqualTo("hashed-password")
        assertThat(repository.savedAdminUser?.passwordHash).isNotEqualTo("password1234")
    }

    @Test
    fun `create admin user rejects duplicate email`() {
        val service = authService(
            adminUser = adminUser(status = AdminUserStatus.ACTIVE),
            passwordMatches = false,
        )

        assertThatThrownBy {
            service.createAdminUser(
                CreateAdminUserCommand(
                    email = "manager@climbdesk.local",
                    password = "password1234",
                    role = AdminUserRole.MANAGER,
                ),
            )
        }.isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DUPLICATE_ADMIN_USER_EMAIL)
    }

    @Test
    fun `change admin user role saves changed role`() {
        val repository = RecordingAdminUserRepository(
            adminUser = adminUser(status = AdminUserStatus.ACTIVE),
            activeManagerCount = 2,
        )
        val service = authService(
            adminUser = null,
            passwordMatches = false,
            repository = repository,
        )

        val result = service.changeAdminUserRole(ChangeAdminUserRoleCommand(1, AdminUserRole.STAFF))

        assertThat(result.role).isEqualTo(AdminUserRole.STAFF)
        assertThat(repository.savedAdminUser?.role).isEqualTo(AdminUserRole.STAFF)
    }

    @Test
    fun `change last active manager to staff fails`() {
        val service = authService(
            adminUser = null,
            passwordMatches = false,
            repository = RecordingAdminUserRepository(
                adminUser = adminUser(status = AdminUserStatus.ACTIVE),
                activeManagerCount = 1,
            ),
        )

        assertThatThrownBy {
            service.changeAdminUserRole(ChangeAdminUserRoleCommand(1, AdminUserRole.STAFF))
        }.isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.LAST_ACTIVE_MANAGER_REQUIRED)
    }

    @Test
    fun `inactive admin user role can be changed`() {
        val repository = RecordingAdminUserRepository(
            adminUser = adminUser(status = AdminUserStatus.INACTIVE),
            activeManagerCount = 1,
        )
        val service = authService(
            adminUser = null,
            passwordMatches = false,
            repository = repository,
        )

        val result = service.changeAdminUserRole(ChangeAdminUserRoleCommand(1, AdminUserRole.STAFF))

        assertThat(result.role).isEqualTo(AdminUserRole.STAFF)
    }

    @Test
    fun `activate admin user saves active status`() {
        val repository = RecordingAdminUserRepository(
            adminUser = adminUser(status = AdminUserStatus.INACTIVE),
            activeManagerCount = 1,
        )
        val service = authService(
            adminUser = null,
            passwordMatches = false,
            repository = repository,
        )

        val result = service.activateAdminUser(1)

        assertThat(result.status).isEqualTo(AdminUserStatus.ACTIVE)
        assertThat(repository.savedAdminUser?.status).isEqualTo(AdminUserStatus.ACTIVE)
    }

    @Test
    fun `deactivate last active manager fails`() {
        val service = authService(
            adminUser = null,
            passwordMatches = false,
            repository = RecordingAdminUserRepository(
                adminUser = adminUser(status = AdminUserStatus.ACTIVE),
                activeManagerCount = 1,
            ),
        )

        assertThatThrownBy {
            service.deactivateAdminUser(1)
        }.isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.LAST_ACTIVE_MANAGER_REQUIRED)
    }

    private fun authService(
        adminUser: AdminUser?,
        passwordMatches: Boolean,
        issuedToken: IssuedAccessToken = IssuedAccessToken("unused-token", 3600),
        tokenIssuer: AccessTokenIssuer = RecordingAccessTokenIssuer(issuedToken),
        repository: AdminUserRepository = StaticAdminUserRepository(adminUser),
        passwordHasher: PasswordHasher = StaticPasswordHasher("unused-hash"),
    ): AuthApplicationService =
        AuthApplicationService(
            adminUserRepository = repository,
            passwordVerifier = StaticPasswordVerifier(passwordMatches),
            passwordHasher = passwordHasher,
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
    override fun findById(id: Long): AdminUser? = adminUser
    override fun findByEmail(email: String): AdminUser? = adminUser
    override fun existsByEmail(email: String): Boolean = adminUser?.email == email
    override fun countByStatusAndRole(
        status: AdminUserStatus,
        role: AdminUserRole,
    ): Long = if (adminUser?.status == status && adminUser.role == role) 1 else 0

    override fun save(adminUser: AdminUser): AdminUser = adminUser
}

private class StaticPasswordVerifier(
    private val matches: Boolean,
) : PasswordVerifier {
    override fun matches(rawPassword: String, passwordHash: String): Boolean = matches
}

private class StaticPasswordHasher(
    private val hash: String,
) : PasswordHasher {
    override fun hash(rawPassword: String): String = hash
}

private class RecordingAdminUserRepository(
    private val adminUser: AdminUser? = null,
    private val activeManagerCount: Long = 0,
) : AdminUserRepository {
    var savedAdminUser: AdminUser? = null
        private set

    override fun findById(id: Long): AdminUser? = adminUser

    override fun findByEmail(email: String): AdminUser? = null

    override fun existsByEmail(email: String): Boolean = false

    override fun countByStatusAndRole(
        status: AdminUserStatus,
        role: AdminUserRole,
    ): Long = activeManagerCount

    override fun save(adminUser: AdminUser): AdminUser {
        savedAdminUser = adminUser
        return adminUser.copy(id = 10, createdAt = java.time.Instant.parse("2026-05-01T01:00:00Z"))
    }
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
