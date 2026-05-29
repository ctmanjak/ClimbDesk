package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
import dev.climbdesk.pass.domain.MemberPassRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassProductType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.auth.jwt.expires-in=3600",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
class MemberPassPersistenceAdapterIntegrationTest @Autowired constructor(
    private val memberJpaRepository: MemberJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
    private val memberPassJpaRepository: MemberPassJpaRepository,
    private val memberPassRepository: MemberPassRepository,
) {
    @BeforeEach
    fun setUp() {
        clearData()
    }

    @AfterEach
    fun tearDown() {
        clearData()
    }

    private fun clearData() {
        memberPassJpaRepository.deleteAll()
        passProductJpaRepository.deleteAll()
        memberJpaRepository.deleteAll()
    }

    @Test
    fun `available pass selection ignores unavailable passes`() {
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val member = saveMember()
        val passProduct = savePassProduct()
        saveMemberPass(member = member, passProduct = passProduct, status = MemberPassStatus.EXHAUSTED, remainingCount = 0)
        saveMemberPass(member = member, passProduct = passProduct, status = MemberPassStatus.EXPIRED, remainingCount = 5)
        saveMemberPass(member = member, passProduct = passProduct, status = MemberPassStatus.CANCELED, remainingCount = 5)
        saveMemberPass(member = member, passProduct = passProduct, remainingCount = 0)
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            remainingCount = 5,
            issuedAt = now.minus(20, ChronoUnit.DAYS),
            expiresAt = now.minus(1, ChronoUnit.DAYS),
        )
        val selected = saveMemberPass(
            member = member,
            passProduct = passProduct,
            remainingCount = 5,
            issuedAt = now.minus(1, ChronoUnit.DAYS),
            expiresAt = now.plus(10, ChronoUnit.DAYS),
        )

        val actual = memberPassRepository.findAvailablePassForUse(member.id, now)

        assertThat(actual?.id).isEqualTo(selected.id)
    }

    @Test
    fun `available pass selection applies expires issued and id ordering`() {
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val member = saveMember()
        val passProduct = savePassProduct()
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = now.minus(10, ChronoUnit.DAYS),
            expiresAt = null,
        )
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = now.minus(20, ChronoUnit.DAYS),
            expiresAt = now.plus(10, ChronoUnit.DAYS),
        )
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = now.minus(5, ChronoUnit.DAYS),
            expiresAt = now.plus(3, ChronoUnit.DAYS),
        )
        val selected = saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = now.minus(30, ChronoUnit.DAYS),
            expiresAt = now.plus(3, ChronoUnit.DAYS),
        )
        saveMemberPass(
            member = member,
            passProduct = passProduct,
            issuedAt = selected.issuedAt,
            expiresAt = selected.expiresAt,
        )

        val actual = memberPassRepository.findAvailablePassForUse(member.id, now)

        assertThat(actual?.id).isEqualTo(selected.id)
    }

    private fun saveMember(): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Hong Gil Dong",
                phone = "010-${System.nanoTime()}",
                email = null,
                status = MemberStatus.ACTIVE,
            ),
        )

    private fun savePassProduct(): PassProductJpaEntity =
        passProductJpaRepository.saveAndFlush(
            PassProductJpaEntity(
                name = "10 Count Pass",
                type = PassProductType.COUNT_PASS,
                totalCount = 10,
                price = null,
                validDays = null,
            ),
        )

    private fun saveMemberPass(
        member: MemberJpaEntity,
        passProduct: PassProductJpaEntity,
        status: MemberPassStatus = MemberPassStatus.ACTIVE,
        remainingCount: Int = passProduct.totalCount,
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant? = issuedAt.plus(90, ChronoUnit.DAYS),
    ): MemberPassJpaEntity =
        memberPassJpaRepository.saveAndFlush(
            MemberPassJpaEntity(
                memberId = member.id,
                passProductId = passProduct.id,
                productNameSnapshot = passProduct.name,
                passTypeSnapshot = passProduct.type,
                totalCount = passProduct.totalCount,
                remainingCount = remainingCount,
                priceSnapshot = passProduct.price,
                validDaysSnapshot = passProduct.validDays,
                status = status,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
            ),
        )
}
