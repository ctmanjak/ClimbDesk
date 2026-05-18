package dev.climbdesk.auth.infrastructure.persistence

import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(
    properties = [
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
    ],
)
class AdminUserPersistenceAdapterTest @Autowired constructor(
    private val adminUserJpaRepository: AdminUserJpaRepository,
) {
    private val adapter = AdminUserPersistenceAdapter(adminUserJpaRepository)

    @Test
    fun `findByEmail loads admin user from database`() {
        adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "manager@climbdesk.local",
                passwordHash = "hashed-password",
                role = AdminUserRole.MANAGER,
                status = AdminUserStatus.ACTIVE,
            ),
        )

        val adminUser = adapter.findByEmail("manager@climbdesk.local")

        assertThat(adminUser).isNotNull
        assertThat(adminUser?.email).isEqualTo("manager@climbdesk.local")
        assertThat(adminUser?.passwordHash).isEqualTo("hashed-password")
        assertThat(adminUser?.role).isEqualTo(AdminUserRole.MANAGER)
        assertThat(adminUser?.status).isEqualTo(AdminUserStatus.ACTIVE)
    }

    @Test
    fun `findByEmail returns null when admin user does not exist`() {
        assertThat(adapter.findByEmail("missing@climbdesk.local")).isNull()
    }
}
