package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.event.application.OutboxEventStore
import dev.climbdesk.event.application.PollingOutboxPublisher
import dev.climbdesk.event.domain.OutboxEvent
import dev.climbdesk.event.domain.OutboxEventStatus
import dev.climbdesk.event.domain.OutboxPublishTarget
import dev.climbdesk.event.infrastructure.persistence.OutboxEventJpaRepository
import dev.climbdesk.event.infrastructure.persistence.toDomain
import dev.climbdesk.event.infrastructure.persistence.toJpaEntity
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.messaging.rabbitmq.enabled=true",
        "climbdesk.messaging.rabbitmq.publisher-enabled=true",
        "climbdesk.messaging.rabbitmq.listener-enabled=false",
        "climbdesk.messaging.rabbitmq.publisher.poll-interval=1h",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///outbox-publisher-rabbitmq",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
class OutboxPublisherRabbitMqIntegrationTest @Autowired constructor(
    private val pollingOutboxPublisher: PollingOutboxPublisher,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val rabbitTemplate: RabbitTemplate,
    private val rabbitAdmin: RabbitAdmin,
    private val objectMapper: ObjectMapper,
) {
    @MockitoSpyBean
    private lateinit var outboxEventStore: OutboxEventStore

    @BeforeEach
    fun setUp() {
        rabbitTemplate.awaitAmqpReady()
        outboxEventJpaRepository.deleteAll()
        rabbitAdmin.declareBinding(mainBinding())
        rabbitAdmin.purgeQueue(RabbitMqTopology.MAIN_QUEUE, false)
    }

    @AfterEach
    fun tearDown() {
        Mockito.reset(outboxEventStore)
        rabbitAdmin.declareBinding(mainBinding())
        rabbitAdmin.purgeQueue(RabbitMqTopology.MAIN_QUEUE, false)
        outboxEventJpaRepository.deleteAll()
    }

    @Test
    fun `publisher records published only after confirmed routed persistent message`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        val outboxEvent = savePendingOutbox(now.minusSeconds(1))
        val startedAt = Instant.now()

        assertThat(pollingOutboxPublisher.publishNext(now)).isTrue()

        val completedAt = Instant.now()
        val persisted = outboxEventJpaRepository.findById(outboxEvent.id).orElseThrow()
        assertThat(persisted.status).isEqualTo(OutboxEventStatus.PUBLISHED)
        assertThat(persisted.publishedAt).isBetween(startedAt, completedAt)
        assertThat(persisted.nextRetryAt).isNull()
        assertThat(persisted.lastError).isNull()

        val message = receiveMainQueue()
        val envelope = objectMapper.readTree(message.body)
        assertThat(message.messageProperties.messageId).isEqualTo(outboxEvent.id.toString())
        assertThat(message.messageProperties.type).isEqualTo("reservation.confirmed")
        assertThat(message.messageProperties.contentType).isEqualTo("application/json")
        assertThat(message.messageProperties.receivedDeliveryMode).isEqualTo(MessageDeliveryMode.PERSISTENT)
        assertThat(message.messageProperties.headers["x-schema-version"]).isEqualTo(1)
        assertThat(message.messageProperties.headers["x-producer"]).isEqualTo("climbdesk")
        assertThat(envelope["eventId"].longValue()).isEqualTo(outboxEvent.id)
        assertThat(envelope["eventType"].textValue()).isEqualTo("reservation.confirmed")
        assertThat(envelope["schemaVersion"].intValue()).isEqualTo(1)
        assertThat(envelope["producer"].textValue()).isEqualTo("climbdesk")
        assertThat(envelope["payload"]["reservationId"].longValue()).isEqualTo(101L)
    }

    @Test
    fun `mandatory return remains failed even when the broker confirms the publish`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        val outboxEvent = savePendingOutbox(now.minusSeconds(1))
        rabbitAdmin.removeBinding(mainBinding())
        val startedAt = Instant.now()

        try {
            assertThat(pollingOutboxPublisher.publishNext(now)).isTrue()

            val completedAt = Instant.now()
            val persisted = outboxEventJpaRepository.findById(outboxEvent.id).orElseThrow()
            assertThat(persisted.status).isEqualTo(OutboxEventStatus.FAILED)
            assertThat(persisted.retryCount).isEqualTo(1)
            assertThat(persisted.nextRetryAt)
                .isBetween(startedAt.plusSeconds(5), completedAt.plusSeconds(5))
            assertThat(persisted.lastError).startsWith("RabbitMQ mandatory return:")
            assertThat(rabbitTemplate.receive(RabbitMqTopology.MAIN_QUEUE, 200)).isNull()
        } finally {
            rabbitAdmin.declareBinding(mainBinding())
        }
    }

    @Test
    fun `confirmed publish followed by database status failure can publish the same event id twice`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        val outboxEvent = savePendingOutbox(now.minusSeconds(1))
        val failFirstPublishedSave = AtomicBoolean(true)
        Mockito.doAnswer { invocation ->
            val result = invocation.callRealMethod()
            val savedEvent = invocation.getArgument<OutboxEvent>(0)
            if (savedEvent.status == OutboxEventStatus.PUBLISHED && failFirstPublishedSave.compareAndSet(true, false)) {
                throw IllegalStateException("test-only database status failure")
            }
            result
        }.`when`(outboxEventStore).save(Mockito.any(OutboxEvent::class.java) ?: outboxEvent)

        assertThatThrownBy {
            pollingOutboxPublisher.publishNext(now)
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(outboxEventJpaRepository.findById(outboxEvent.id).orElseThrow().status)
            .isEqualTo(OutboxEventStatus.PENDING)
        assertThat(pollingOutboxPublisher.publishNext(now.plusSeconds(1))).isTrue()
        assertThat(outboxEventJpaRepository.findById(outboxEvent.id).orElseThrow().status)
            .isEqualTo(OutboxEventStatus.PUBLISHED)

        val first = receiveMainQueue()
        val second = receiveMainQueue()
        assertThat(first.messageProperties.messageId).isEqualTo(outboxEvent.id.toString())
        assertThat(second.messageProperties.messageId).isEqualTo(outboxEvent.id.toString())
        assertThat(objectMapper.readTree(first.body)["eventId"].longValue()).isEqualTo(outboxEvent.id)
        assertThat(objectMapper.readTree(second.body)["eventId"].longValue()).isEqualTo(outboxEvent.id)
    }

    private fun savePendingOutbox(occurredAt: Instant): OutboxEvent =
        outboxEventJpaRepository.saveAndFlush(
            OutboxEvent.pending(
                eventType = "ReservationConfirmedEvent",
                aggregateType = "Reservation",
                aggregateId = 101L,
                payload =
                    objectMapper.writeValueAsString(
                        ReservationConfirmedEvent(
                            reservationId = 101L,
                            memberId = 201L,
                            classSessionId = 301L,
                            memberPassId = 401L,
                            occurredAt = occurredAt,
                        ),
                    ),
                occurredAt = occurredAt,
                publishTarget = OutboxPublishTarget.RABBITMQ,
            ).toJpaEntity(),
        ).toDomain()

    private fun receiveMainQueue(): Message =
        rabbitTemplate.receive(RabbitMqTopology.MAIN_QUEUE, 5_000)
            ?: error("No message received from ${RabbitMqTopology.MAIN_QUEUE}")

    private fun mainBinding(): Binding =
        BindingBuilder.bind(Queue(RabbitMqTopology.MAIN_QUEUE, true))
            .to(TopicExchange(RabbitMqTopology.MAIN_EXCHANGE, true, false))
            .with(RabbitMqTopology.MAIN_ROUTING_KEY)

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
        }
    }
}
