package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaEntity
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaRepository
import dev.climbdesk.event.application.PollingOutboxPublisher
import dev.climbdesk.event.domain.OutboxEventStatus
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventEnvelopeV1
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventPayloadV1
import dev.climbdesk.event.infrastructure.persistence.OutboxEventJpaRepository
import dev.climbdesk.event.infrastructure.persistence.ProcessedEventJpaRepository
import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
import dev.climbdesk.notification.application.ReservationConfirmedNotificationCommand
import dev.climbdesk.notification.application.ReservationConfirmedNotificationUseCase
import dev.climbdesk.notification.application.ReservationNotificationHandlingResult
import dev.climbdesk.notification.infrastructure.persistence.ReservationNotificationRequestJpaRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassProductType
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassUsageHistoryJpaRepository
import dev.climbdesk.reservation.application.CreateReservationCommand
import dev.climbdesk.reservation.application.ReservationApplicationService
import dev.climbdesk.reservation.domain.ReservationStatus
import dev.climbdesk.reservation.infrastructure.persistence.ReservationJpaEntity
import dev.climbdesk.reservation.infrastructure.persistence.ReservationJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.messaging.rabbitmq.enabled=true",
        "climbdesk.messaging.rabbitmq.publisher-enabled=true",
        "climbdesk.messaging.rabbitmq.listener-enabled=true",
        "climbdesk.messaging.rabbitmq.publisher.poll-interval=1h",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///reservation-notification-rabbitmq",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
class ReservationNotificationRabbitMqIntegrationTest @Autowired constructor(
    private val handler: ReservationConfirmedNotificationUseCase,
    private val reservationApplicationService: ReservationApplicationService,
    private val pollingOutboxPublisher: PollingOutboxPublisher,
    private val listenerRegistry: RabbitListenerEndpointRegistry,
    private val applicationContext: ApplicationContext,
    private val rabbitTemplate: RabbitTemplate,
    private val rabbitAdmin: RabbitAdmin,
    private val objectMapper: ObjectMapper,
    private val processedEventRepository: ProcessedEventJpaRepository,
    private val notificationRequestRepository: ReservationNotificationRequestJpaRepository,
    private val outboxEventRepository: OutboxEventJpaRepository,
    private val passUsageHistoryRepository: PassUsageHistoryJpaRepository,
    private val reservationRepository: ReservationJpaRepository,
    private val classSessionRepository: ClassSessionJpaRepository,
    private val memberPassRepository: MemberPassJpaRepository,
    private val passProductRepository: PassProductJpaRepository,
    private val memberRepository: MemberJpaRepository,
) {
    @BeforeEach
    fun setUp() {
        listenerContainer().stop()
        rabbitTemplate.awaitAmqpReady()
        purgeQueues()
        clearData()
    }

    @AfterEach
    fun tearDown() {
        listenerContainer().stop()
        purgeQueues()
        clearData()
    }

    @Test
    fun `database commit followed by connection close before ack redelivers and remains one business result`() {
        val ids = savePrerequisites()
        val reservation = reservationRepository.saveAndFlush(
            ReservationJpaEntity(
                memberId = ids.memberId,
                classSessionId = ids.classSessionId,
                memberPassId = ids.memberPassId,
                status = ReservationStatus.CONFIRMED,
                reservedAt = Instant.now(),
            ),
        )
        val envelope = envelope(EVENT_ID, reservation.id, ids)
        publish(envelope)

        val firstConnection = newRawConnection()
        val firstChannel = firstConnection.createChannel()
        val firstDelivery = receiveWithoutAck(firstChannel)
        assertThat(firstDelivery.envelope.isRedeliver).isFalse()

        assertThat(handler.handle(envelope.toCommand()))
            .isEqualTo(ReservationNotificationHandlingResult.PROCESSED)
        assertThat(processedEventRepository.count()).isEqualTo(1)
        assertThat(notificationRequestRepository.count()).isEqualTo(1)

        firstConnection.close()

        val secondConnection = newRawConnection()
        try {
            val secondChannel = secondConnection.createChannel()
            val redelivery = receiveWithoutAck(secondChannel)
            assertThat(redelivery.envelope.isRedeliver).isTrue()
            assertThat(objectMapper.readValue(redelivery.body, ReservationConfirmedEventEnvelopeV1::class.java).eventId)
                .isEqualTo(EVENT_ID)
            assertThat(handler.handle(envelope.toCommand()))
                .isEqualTo(ReservationNotificationHandlingResult.DUPLICATE)

            secondChannel.basicAck(redelivery.envelope.deliveryTag, false)
            awaitQueueCount(0)
        } finally {
            secondConnection.close()
        }

        assertThat(processedEventRepository.count()).isEqualTo(1)
        assertThat(notificationRequestRepository.count()).isEqualTo(1)
    }

    @Test
    fun `reservation and outbox publish continue while consumer is stopped and backlog drains after restart`() {
        assertThat(applicationContext.getBeansOfType(ReservationConfirmedEventListener::class.java)).hasSize(1)
        assertThat(listenerContainer().isRunning).isFalse()
        val ids = savePrerequisites()

        val reservation = reservationApplicationService.reserveClass(
            CreateReservationCommand(
                memberId = ids.memberId,
                classSessionId = ids.classSessionId,
            ),
        )
        val outbox = outboxEventRepository.findAll().single()
        assertThat(outbox.status).isEqualTo(OutboxEventStatus.PENDING)
        assertThat(pollingOutboxPublisher.publishNext(Instant.now().plusSeconds(1))).isTrue()
        assertThat(outboxEventRepository.findById(outbox.id).orElseThrow().status)
            .isEqualTo(OutboxEventStatus.PUBLISHED)

        awaitQueueCount(1)
        assertThat(processedEventRepository.count()).isZero()
        assertThat(notificationRequestRepository.count()).isZero()

        listenerContainer().start()

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted {
            assertThat(processedEventRepository.count()).isEqualTo(1)
            assertThat(notificationRequestRepository.count()).isEqualTo(1)
            assertThat(queueCount()).isZero()
        }
        val notification = notificationRequestRepository.findAll().single()
        assertThat(notification.sourceEventId).isEqualTo(outbox.id)
        assertThat(notification.reservationId).isEqualTo(reservation.id)
        assertThat(notification.memberId).isEqualTo(ids.memberId)
    }

    private fun publish(envelope: ReservationConfirmedEventEnvelopeV1) {
        val properties = MessageProperties().apply {
            messageId = envelope.eventId.toString()
            type = "reservation.confirmed"
            contentType = MessageProperties.CONTENT_TYPE_JSON
            deliveryMode = MessageDeliveryMode.PERSISTENT
            setHeader("x-schema-version", 1)
            setHeader("x-producer", "climbdesk")
        }
        rabbitTemplate.send(
            RabbitMqTopology.MAIN_EXCHANGE,
            RabbitMqTopology.MAIN_ROUTING_KEY,
            Message(objectMapper.writeValueAsBytes(envelope), properties),
        )
    }

    private fun envelope(
        eventId: Long,
        reservationId: Long,
        ids: ReservationIds,
    ): ReservationConfirmedEventEnvelopeV1 =
        ReservationConfirmedEventEnvelopeV1(
            eventId = eventId,
            eventType = "reservation.confirmed",
            schemaVersion = 1,
            producer = "climbdesk",
            aggregateType = "Reservation",
            aggregateId = reservationId,
            occurredAt = Instant.now(),
            payload =
                ReservationConfirmedEventPayloadV1(
                    reservationId = reservationId,
                    memberId = ids.memberId,
                    classSessionId = ids.classSessionId,
                    memberPassId = ids.memberPassId,
                ),
        )

    private fun ReservationConfirmedEventEnvelopeV1.toCommand() =
        ReservationConfirmedNotificationCommand(
            eventId = eventId,
            reservationId = payload.reservationId,
            memberId = payload.memberId,
            classSessionId = payload.classSessionId,
            memberPassId = payload.memberPassId,
        )

    private fun receiveWithoutAck(channel: com.rabbitmq.client.Channel): GetResponse {
        val received = AtomicReference<GetResponse>()
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted {
            channel.basicGet(RabbitMqTopology.MAIN_QUEUE, false)?.let(received::set)
            assertThat(received.get()).isNotNull
        }
        return received.get()
    }

    private fun newRawConnection(): Connection =
        ConnectionFactory().apply {
            host = rabbitMq.host
            port = rabbitMq.amqpPort
            username = rabbitMq.adminUsername
            password = rabbitMq.adminPassword
            virtualHost = "/"
        }.newConnection()

    private fun savePrerequisites(): ReservationIds {
        val now = Instant.now()
        val member = memberRepository.saveAndFlush(
            MemberJpaEntity(
                name = "Consumer Test Member",
                phone = "01012345678",
                email = null,
                status = MemberStatus.ACTIVE,
            ),
        )
        val passProduct = passProductRepository.saveAndFlush(
            PassProductJpaEntity(
                name = "Consumer Test Pass",
                type = PassProductType.COUNT_PASS,
                totalCount = 10,
                price = null,
                validDays = 90,
            ),
        )
        val classSession = classSessionRepository.saveAndFlush(
            ClassSessionJpaEntity(
                title = "Consumer Test Class",
                startsAt = now.plus(1, ChronoUnit.DAYS),
                endsAt = now.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS),
                capacity = 10,
                reservedCount = 0,
                status = ClassSessionStatus.OPEN,
            ),
        )
        val memberPass = memberPassRepository.saveAndFlush(
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
                issuedAt = now.minus(1, ChronoUnit.DAYS),
                expiresAt = now.plus(90, ChronoUnit.DAYS),
            ),
        )
        return ReservationIds(member.id, classSession.id, memberPass.id)
    }

    private fun listenerContainer() =
        checkNotNull(listenerRegistry.getListenerContainer(ReservationConfirmedEventListener.LISTENER_ID))

    private fun awaitQueueCount(expected: Int) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted {
            assertThat(queueCount()).isEqualTo(expected)
        }
    }

    private fun queueCount(): Int =
        checkNotNull(rabbitAdmin.getQueueInfo(RabbitMqTopology.MAIN_QUEUE)).messageCount

    private fun purgeQueues() {
        allQueues.forEach { rabbitAdmin.purgeQueue(it, false) }
    }

    private fun clearData() {
        notificationRequestRepository.deleteAll()
        processedEventRepository.deleteAll()
        outboxEventRepository.deleteAll()
        passUsageHistoryRepository.deleteAll()
        reservationRepository.deleteAll()
        classSessionRepository.deleteAll()
        memberPassRepository.deleteAll()
        passProductRepository.deleteAll()
        memberRepository.deleteAll()
    }

    data class ReservationIds(
        val memberId: Long,
        val classSessionId: Long,
        val memberPassId: Long,
    )

    companion object {
        const val EVENT_ID = 701L
        val allQueues =
            listOf(
                RabbitMqTopology.MAIN_QUEUE,
                RabbitMqTopology.RETRY_QUEUE_5S,
                RabbitMqTopology.RETRY_QUEUE_30S,
                RabbitMqTopology.RETRY_QUEUE_2M,
                RabbitMqTopology.DEAD_LETTER_QUEUE,
            )

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
        }
    }
}
