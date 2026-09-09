package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.auth.domain.AdminUserRole
import dev.climbdesk.auth.domain.AdminUserStatus
import dev.climbdesk.auth.infrastructure.adapter.Pbkdf2PasswordVerifier
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaEntity
import dev.climbdesk.auth.infrastructure.persistence.AdminUserJpaRepository
import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaEntity
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaRepository
import dev.climbdesk.event.domain.OutboxEventStatus
import dev.climbdesk.event.infrastructure.persistence.OutboxEventJpaRepository
import dev.climbdesk.event.infrastructure.persistence.ProcessedEventJpaRepository
import dev.climbdesk.notification.infrastructure.persistence.ReservationNotificationRequestJpaRepository
import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassProductType
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassUsageHistoryJpaRepository
import dev.climbdesk.reservation.infrastructure.persistence.ReservationJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.messaging.rabbitmq.enabled=true",
        "climbdesk.messaging.rabbitmq.publisher-enabled=true",
        "climbdesk.messaging.rabbitmq.listener-enabled=true",
        "climbdesk.messaging.rabbitmq.publisher.poll-interval=100ms",
        "climbdesk.messaging.rabbitmq.publisher.confirm-timeout=500ms",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///outbox-publisher-recovery",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@AutoConfigureMockMvc
class OutboxPublisherBrokerRecoveryIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val rabbitAdmin: RabbitAdmin,
    private val rabbitTemplate: RabbitTemplate,
    private val connectionFactory: CachingConnectionFactory,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
    private val passProductJpaRepository: PassProductJpaRepository,
    private val memberPassJpaRepository: MemberPassJpaRepository,
    private val classSessionJpaRepository: ClassSessionJpaRepository,
    private val reservationJpaRepository: ReservationJpaRepository,
    private val passUsageHistoryJpaRepository: PassUsageHistoryJpaRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val processedEventJpaRepository: ProcessedEventJpaRepository,
    private val notificationRequestJpaRepository: ReservationNotificationRequestJpaRepository,
) {
    @BeforeEach
    fun setUp() {
        rabbitTemplate.awaitAmqpReady(rabbitMq)
        clearData()
        rabbitAdmin.purgeQueue(RabbitMqTopology.MAIN_QUEUE, false)
    }

    @AfterEach
    fun tearDown() {
        ensureRabbitMqRunning()
        connectionFactory.resetConnection()
        rabbitTemplate.awaitAmqpReady(rabbitMq)
        rabbitAdmin.purgeQueue(RabbitMqTopology.MAIN_QUEUE, false)
        clearData()
    }

    @Test
    fun `reservation and outbox commit while broker is down and scheduler publishes after recovery`() {
        val token = accessToken()
        val member = saveMember()
        val classSession = saveClassSession()
        saveMemberPass(member)
        pauseRabbitMq()

        try {
            mockMvc.post("/api/v1/reservations") {
                contentType = MediaType.APPLICATION_JSON
                header("Authorization", "Bearer $token")
                content = """{"memberId":${member.id},"classSessionId":${classSession.id}}"""
            }.andExpect {
                status { isCreated() }
            }

            assertThat(reservationJpaRepository.count()).isEqualTo(1)
            assertThat(outboxEventJpaRepository.count()).isEqualTo(1)
            await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).untilAsserted {
                val outbox = outboxEventJpaRepository.findAll().single()
                assertThat(outbox.status).isEqualTo(OutboxEventStatus.FAILED)
                assertThat(outbox.retryCount).isEqualTo(1)
                assertThat(outbox.nextRetryAt).isNotNull()
            }
        } finally {
            ensureRabbitMqRunning()
            connectionFactory.resetConnection()
            rabbitTemplate.awaitAmqpReady(rabbitMq)
        }

        await().atMost(Duration.ofSeconds(12)).pollInterval(Duration.ofMillis(200)).untilAsserted {
            val outbox = outboxEventJpaRepository.findAll().single()
            assertThat(outbox.status).isEqualTo(OutboxEventStatus.PUBLISHED)
            assertThat(outbox.publishedAt).isNotNull()
            assertThat(processedEventJpaRepository.count()).isEqualTo(1)
            assertThat(notificationRequestJpaRepository.count()).isEqualTo(1)
            assertThat(checkNotNull(rabbitAdmin.getQueueInfo(RabbitMqTopology.MAIN_QUEUE)).messageCount).isZero()
        }
        val eventId = outboxEventJpaRepository.findAll().single().id
        assertThat(processedEventJpaRepository.findAll().single().eventId).isEqualTo(eventId)
        assertThat(notificationRequestJpaRepository.findAll().single().sourceEventId).isEqualTo(eventId)
    }

    private fun accessToken(): String {
        adminUserJpaRepository.saveAndFlush(
            AdminUserJpaEntity(
                email = "manager-broker-recovery@climbdesk.local",
                passwordHash = Pbkdf2PasswordVerifier.encode("password1234"),
                role = AdminUserRole.MANAGER,
                status = AdminUserStatus.ACTIVE,
            ),
        )
        val response =
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"manager-broker-recovery@climbdesk.local","password":"password1234"}"""
            }.andExpect {
                status { isOk() }
            }.andReturn().response.contentAsString
        return objectMapper.readTree(response)["accessToken"].asText()
    }

    private fun saveMember(): MemberJpaEntity =
        memberJpaRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Broker Recovery Member",
                phone = "01099990000",
                email = null,
                status = MemberStatus.ACTIVE,
            ),
        )

    private fun saveClassSession(): ClassSessionJpaEntity =
        classSessionJpaRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = "Broker Recovery Class",
                startsAt = Instant.now().plus(1, ChronoUnit.DAYS),
                endsAt = Instant.now().plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS),
                capacity = 10,
                reservedCount = 0,
                status = ClassSessionStatus.OPEN,
            ),
        )

    private fun saveMemberPass(member: MemberJpaEntity): MemberPassJpaEntity {
        val passProduct =
            passProductJpaRepository.saveAndFlush(
                PassProductJpaEntity(
                    name = "Broker Recovery Pass",
                    type = PassProductType.COUNT_PASS,
                    totalCount = 10,
                    price = null,
                    validDays = 90,
                ),
            )
        val issuedAt = Instant.now()
        return memberPassJpaRepository.saveAndFlush(
            MemberPassJpaEntity(
                memberId = member.id,
                passProductId = passProduct.id,
                productNameSnapshot = passProduct.name,
                passTypeSnapshot = passProduct.type,
                totalCount = passProduct.totalCount,
                remainingCount = 10,
                priceSnapshot = passProduct.price,
                validDaysSnapshot = passProduct.validDays,
                status = MemberPassStatus.ACTIVE,
                issuedAt = issuedAt,
                expiresAt = issuedAt.plus(90, ChronoUnit.DAYS),
            ),
        )
    }

    private fun pauseRabbitMq() {
        rabbitMq.dockerClient.pauseContainerCmd(rabbitMq.containerId).exec()
    }

    private fun ensureRabbitMqRunning() {
        val state = rabbitMq.dockerClient.inspectContainerCmd(rabbitMq.containerId).exec().state
        if (state.paused == true) {
            rabbitMq.dockerClient.unpauseContainerCmd(rabbitMq.containerId).exec()
        }
    }

    private fun clearData() {
        notificationRequestJpaRepository.deleteAll()
        processedEventJpaRepository.deleteAll()
        outboxEventJpaRepository.deleteAll()
        passUsageHistoryJpaRepository.deleteAll()
        reservationJpaRepository.deleteAll()
        classSessionJpaRepository.deleteAll()
        memberPassJpaRepository.deleteAll()
        passProductJpaRepository.deleteAll()
        memberJpaRepository.deleteAll()
        adminUserJpaRepository.deleteAll()
    }

    companion object {
        @Container
        @JvmStatic
        val rabbitMq: RabbitMQContainer =
            RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"))

        @JvmStatic
        @DynamicPropertySource
        fun registerRabbitProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.rabbitmq.host", rabbitMq::getHost)
            registry.add("spring.rabbitmq.port", rabbitMq::getAmqpPort)
            registry.add("spring.rabbitmq.username", rabbitMq::getAdminUsername)
            registry.add("spring.rabbitmq.password", rabbitMq::getAdminPassword)
            registry.add("spring.rabbitmq.virtual-host") { "/" }
            registry.add("climbdesk.messaging.rabbitmq.observability.management-base-url") {
                "http://${rabbitMq.host}:${rabbitMq.getMappedPort(15672)}"
            }
        }
    }
}
