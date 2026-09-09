package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaEntity
import dev.climbdesk.classsession.infrastructure.persistence.ClassSessionJpaRepository
import dev.climbdesk.event.application.PollingOutboxPublisher
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventEnvelopeV1
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventPayloadV1
import dev.climbdesk.event.infrastructure.persistence.OutboxEventJpaRepository
import dev.climbdesk.event.infrastructure.observability.RabbitMqQueueMetrics
import io.micrometer.core.instrument.MeterRegistry
import dev.climbdesk.event.infrastructure.persistence.ProcessedEventJpaRepository
import dev.climbdesk.member.domain.MemberStatus
import dev.climbdesk.member.infrastructure.persistence.MemberJpaEntity
import dev.climbdesk.member.infrastructure.persistence.MemberJpaRepository
import dev.climbdesk.notification.infrastructure.persistence.ReservationNotificationRequestJpaRepository
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassProductType
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.MemberPassJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaEntity
import dev.climbdesk.pass.infrastructure.persistence.PassProductJpaRepository
import dev.climbdesk.pass.infrastructure.persistence.PassUsageHistoryJpaRepository
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.amqp.core.Binding
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import dev.climbdesk.notification.application.ReservationNotificationRequestStore
import dev.climbdesk.notification.domain.ReservationNotificationRequest
import dev.climbdesk.notification.domain.ReservationNotificationStatus
import dev.climbdesk.notification.domain.ReservationNotificationType
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension::class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.messaging.rabbitmq.enabled=true",
        "climbdesk.messaging.rabbitmq.publisher-enabled=false",
        "climbdesk.messaging.rabbitmq.listener-enabled=true",
        "climbdesk.messaging.rabbitmq.publisher.poll-interval=1h",
        "climbdesk.messaging.rabbitmq.observability.sample-interval=1h",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///reservation-notification-retry-rabbitmq",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
class ReservationNotificationRetryRabbitMqIntegrationTest @Autowired constructor(
    private val listener: ReservationConfirmedEventListener,
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
    private val queueMetrics: RabbitMqQueueMetrics,
    private val meterRegistry: MeterRegistry,
) {
    @BeforeEach
    fun setUp() {
        listenerContainer().stop()
        rabbitTemplate.awaitAmqpReady(rabbitMq)
        purgeQueues()
        clearData()
        publishedFailures.clear()
        Mockito.doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(processedEventRepository.count()).isZero()
            assertThat(notificationRequestRepository.count()).isZero()
            it.callRealMethod()
            publishedFailures.add(it.getArgument(0))
            null
        }.`when`(failurePublisher).publish(Mockito.any(ReservationNotificationFailureRoute::class.java)
            ?: ReservationNotificationFailureRoute("", "", Message(ByteArray(0))))
    }

    @AfterEach
    fun tearDown() {
        listenerContainer().stop()
        purgeQueues()
        clearData()
    }

    @MockitoSpyBean
    private lateinit var notificationStore: ReservationNotificationRequestStore

    @MockitoSpyBean
    private lateinit var handler: dev.climbdesk.notification.application.ReservationConfirmedNotificationHandler

    @MockitoSpyBean
    private lateinit var failurePublisher: RabbitNotificationFailurePublisher

    private val publishedFailures = java.util.concurrent.CopyOnWriteArrayList<ReservationNotificationFailureRoute>()

    @Test
    fun `transient rollback recovers via real 5s TTL with publisher disabled`() {
        assertThat(applicationContext.getBeansOfType(PollingOutboxPublisher::class.java)).isEmpty()
        val envelope = savedEnvelope()
        val attempts = AtomicInteger()
        failStore { if (attempts.incrementAndGet() == 1) throw TransientDataAccessResourceException("private@example.com") }
        val original = message(envelope)
        sendMain(original)
        listenerContainer().start()

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(notificationRequestRepository.count()).isEqualTo(1)
            assertThat(processedEventRepository.count()).isEqualTo(1)
            assertQueuesEmpty()
        }
        listenerContainer().stop()
        assertQueuesEmpty()
        assertThat(attempts.get()).isEqualTo(2)
        assertThat(publishedFailures).hasSize(1)
        val retry = publishedFailures.single()
        assertThat(retry.routingKey).isEqualTo(RabbitMqTopology.RETRY_QUEUE_5S)
        assertPreserved(original, retry.message, 1)
        assertThat(notificationRequestRepository.findAll().single().sourceEventId).isEqualTo(envelope.eventId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["transient", "unknown"])
    fun `production stages route in order and exhausted delivery leaves exactly one DLQ`(kind: String) {
        val original = message(savedEnvelope())
        failStore {
            if (kind == "transient") throw TransientDataAccessResourceException("private@example.com SQL secret")
            throw IllegalStateException("private@example.com SQL secret")
        }
        var delivery = original
        var firstFailedAt: String? = null
        var previousLast: Instant? = null
        for (count in 0..3) {
            sendMain(delivery)
            newRawConnection().use { connection ->
                val channel = connection.createChannel()
                listener.consume(toMessage(receiveWithoutAck(channel)), channel)
            }
            val queue = if (count < 3) RabbitMqTopology.retryQueues[count].name else RabbitMqTopology.DEAD_LETTER_QUEUE
            awaitCount(queue, 1)
            assertThat(publishedFailures.last().routingKey).isEqualTo(queue)
            assertThat(processedEventRepository.count()).isZero()
            assertThat(notificationRequestRepository.count()).isZero()
            // Test-only orchestration drains the real production retry queue and advances its message.
            // Production TTL arguments remain unchanged; the recovery test exercises the actual 5s TTL.
            delivery = checkNotNull(rabbitTemplate.receive(queue, 1000))
            assertPreserved(original, delivery, minOf(count + 1, 3))
            val headers = delivery.messageProperties.headers
            val first = headers["x-first-failed-at"].toString()
            if (firstFailedAt == null) firstFailedAt = first
            assertThat(first).isEqualTo(firstFailedAt)
            val last = Instant.parse(headers["x-last-failed-at"].toString())
            previousLast?.let { assertThat(last).isAfter(it) }
            previousLast = last
            assertThat(headers["x-failure-category"].toString()).isEqualTo(kind.uppercase())
            assertThat(headers.toString()).doesNotContain("private@example.com", "SQL secret", "stackTrace")
        }
        // Restore the single inspected DLQ message to verify it has no automatic return route.
        rabbitTemplate.send(RabbitMqTopology.DEAD_LETTER_EXCHANGE, RabbitMqTopology.DEAD_LETTER_QUEUE, delivery)
        awaitCount(RabbitMqTopology.DEAD_LETTER_QUEUE, 1)
        newRawConnection().use { connection ->
            val channel = connection.createChannel()
            val dlq = channel.queueDeclarePassive(RabbitMqTopology.DEAD_LETTER_QUEUE)
            assertThat(dlq.messageCount).isEqualTo(1)
        }
        allQueues.filter { it != RabbitMqTopology.DEAD_LETTER_QUEUE }.forEach { awaitCount(it, 0) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["json", "missing", "version", "messageId", "type", "schemaHeader", "retryHeader"])
    fun `permanent message goes directly to one DLQ without business rows`(kind: String, output: CapturedOutput) {
        var original = message(savedEnvelope())
        original = when (kind) {
            "json" -> Message("{\"email\":\"private@example.com\"".toByteArray(), original.messageProperties)
            "missing" -> {
                val tree = objectMapper.readTree(original.body) as com.fasterxml.jackson.databind.node.ObjectNode
                tree.remove("occurredAt")
                Message(objectMapper.writeValueAsBytes(tree), original.messageProperties)
            }
            "version" -> message(savedEnvelopeWithoutSaving().copy(schemaVersion = 2))
            else -> original.apply {
                when (kind) {
                    "messageId" -> messageProperties.messageId = "999"
                    "type" -> messageProperties.type = "unknown"
                    "schemaHeader" -> messageProperties.setHeader("x-schema-version", 2)
                    "retryHeader" -> messageProperties.setHeader("x-retry-count", "bad-count")
                }
            }
        }
        sendMain(original)
        listenerContainer().start()
        awaitCount(RabbitMqTopology.DEAD_LETTER_QUEUE, 1)
        listenerContainer().stop()
        val dlq = checkNotNull(rabbitTemplate.receive(RabbitMqTopology.DEAD_LETTER_QUEUE, 1000))
        assertThat(dlq.body).isEqualTo(original.body)
        assertThat(dlq.messageProperties.messageId).isEqualTo(original.messageProperties.messageId)
        assertThat(dlq.messageProperties.type).isEqualTo(original.messageProperties.type)
        assertThat(dlq.messageProperties.headers["x-schema-version"]).isEqualTo(original.messageProperties.headers["x-schema-version"])
        assertThat(dlq.messageProperties.headers["x-failure-category"].toString()).isEqualTo("PERMANENT_MESSAGE")
        assertThat(dlq.messageProperties.headers.toString()).doesNotContain("private@example.com", "stackTrace")
        assertThat(output.all).doesNotContain("private@example.com")
        assertThat(processedEventRepository.count()).isZero()
        assertThat(notificationRequestRepository.count()).isZero()
        assertThat(publishedFailures).hasSize(1)
        assertQueuesEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "identity"])
    fun `reservation consistency errors roll back processed insert and go directly to DLQ`(kind: String) {
        val saved = savedEnvelope()
        val invalid = if (kind == "missing") {
            saved.copy(aggregateId = Long.MAX_VALUE, payload = saved.payload.copy(reservationId = Long.MAX_VALUE))
        } else {
            saved.copy(payload = saved.payload.copy(memberId = saved.payload.memberId + 1))
        }
        val original = message(invalid)
        sendMain(original)
        listenerContainer().start()
        awaitCount(RabbitMqTopology.DEAD_LETTER_QUEUE, 1)
        listenerContainer().stop()
        val dlq = checkNotNull(rabbitTemplate.receive(RabbitMqTopology.DEAD_LETTER_QUEUE, 1000))
        assertPreserved(original, dlq, 0)
        assertThat(dlq.messageProperties.headers["x-failure-category"].toString()).isEqualTo("DATA_CONSISTENCY")
        assertThat(processedEventRepository.count()).isZero()
        assertThat(notificationRequestRepository.count()).isZero()
        assertQueuesEmpty()
    }

    @Test
    fun `mandatory return leaves manual delivery unacked and original redelivers after connection close`() {
        val original = message(savedEnvelope())
        failStore { throw TransientDataAccessResourceException("private@example.com") }
        val binding = retryBinding()
        rabbitAdmin.removeBinding(binding)
        try {
            sendMain(original)
            newRawConnection().use { connection ->
                val channel = connection.createChannel()
                val delivery = receiveWithoutAck(channel)
                assertThatThrownBy { listener.consume(toMessage(delivery), channel) }
                    .isInstanceOfSatisfying(FailureRepublishException::class.java) {
                        assertThat(it.reason).isEqualTo(FailureRepublishReason.MANDATORY_RETURN)
                    }
                assertThat(channel.basicGet(RabbitMqTopology.MAIN_QUEUE, false)).isNull()
                assertQueuesEmpty()
            }
            newRawConnection().use { connection ->
                val channel = connection.createChannel()
                val redelivery = receiveWithoutAck(channel)
                assertThat(redelivery.envelope.isRedeliver).isTrue()
                assertThat(redelivery.props.messageId).isEqualTo(original.messageProperties.messageId)
                assertThat(redelivery.body).isEqualTo(original.body)
                assertThat(redelivery.props.headers["x-retry-count"]).isNull()
                channel.basicAck(redelivery.envelope.deliveryTag, false)
            }
            assertThat(processedEventRepository.count()).isZero()
            assertThat(notificationRequestRepository.count()).isZero()
        } finally {
            rabbitAdmin.declareBinding(binding)
        }
    }

    @Test
    fun `background container also preserves unconfirmed delivery without default DLX or tight requeue`(output: CapturedOutput) {
        val original = message(savedEnvelope())
        val attempts = AtomicInteger()
        failStore { attempts.incrementAndGet(); throw TransientDataAccessResourceException("private@example.com") }
        val binding = retryBinding()
        rabbitAdmin.removeBinding(binding)
        try {
            sendMain(original)
            listenerContainer().start()
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                assertThat(output.out).contains("Reservation notification delivery remains unacknowledged")
                assertThat(attempts.get()).isEqualTo(1)
            }
            listenerContainer().stop()
            assertThat(output.all).doesNotContain("private@example.com")
            assertThat(attempts.get()).isEqualTo(1)
            newRawConnection().use { connection ->
                val channel = connection.createChannel()
                val redelivery = receiveWithoutAck(channel)
                assertThat(redelivery.envelope.isRedeliver).isTrue()
                assertThat(redelivery.body).isEqualTo(original.body)
                assertThat(redelivery.props.messageId).isEqualTo(original.messageProperties.messageId)
                channel.basicAck(redelivery.envelope.deliveryTag, false)
            }
            assertQueuesEmpty()
        } finally {
            listenerContainer().stop()
            rabbitAdmin.declareBinding(binding)
        }
    }

    @Test
    fun `ten unconfirmed failure republishes saturate prefetch then recover by binding repair and listener restart`() {
        val original = message(savedEnvelope())
        val attempts = AtomicInteger()
        val failing = AtomicBoolean(true)
        failStore {
            attempts.incrementAndGet()
            if (failing.get()) throw TransientDataAccessResourceException("private@example.com")
        }
        val binding = retryBinding()
        val failedBefore = counter("climbdesk.messaging.consumer.failure.republish.failures", "retry")
        val confirmedBefore = counter("climbdesk.messaging.consumer.failure.republish.confirmed", "retry")
        rabbitAdmin.removeBinding(binding)
        try {
            repeat(10) { sendMain(original) }
            listenerContainer().start()

            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted {
                assertThat(attempts.get()).isEqualTo(10)
                queueMetrics.refresh()
                assertThat(queueGauge("climbdesk.messaging.rabbitmq.queue.unacknowledged")).isEqualTo(10.0)
                assertThat(counter("climbdesk.messaging.consumer.failure.republish.failures", "retry") - failedBefore)
                    .isEqualTo(10.0)
                assertThat(counter("climbdesk.messaging.consumer.failure.republish.confirmed", "retry") - confirmedBefore)
                    .isZero()
            }
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted {
                assertThat(attempts.get()).isEqualTo(10)
            }

            listenerContainer().stop()
            rabbitAdmin.declareBinding(binding)
            failing.set(false)
            listenerContainer().start()

            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted {
                queueMetrics.refresh()
                assertThat(queueGauge("climbdesk.messaging.rabbitmq.queue.unacknowledged")).isZero()
                assertThat(processedEventRepository.count()).isEqualTo(1)
                assertThat(notificationRequestRepository.count()).isEqualTo(1)
                assertQueuesEmpty()
            }
            assertThat(attempts.get()).isEqualTo(11)
            assertThat(notificationRequestRepository.findAll().single().sourceEventId).isEqualTo(EVENT_ID)
        } finally {
            listenerContainer().stop()
            rabbitAdmin.declareBinding(binding)
        }
    }

    @Test
    fun `confirmed retry then connection loss before ACK creates duplicates absorbed by DB constraints`() {
        val original = message(savedEnvelope())
        val attempts = AtomicInteger()
        failStore { if (attempts.incrementAndGet() == 1) throw TransientDataAccessResourceException("temporary") }
        sendMain(original)
        val connection = newRawConnection()
        try {
            val rawChannel = connection.createChannel()
            val delivery = toMessage(receiveWithoutAck(rawChannel))
            val channel = Mockito.spy(rawChannel)
            Mockito.doAnswer {
                assertThat(publishedFailures).hasSize(1)
                connection.close()
                throw java.io.IOException("ACK interrupted by connection loss")
            }.`when`(channel).basicAck(delivery.messageProperties.deliveryTag, false)
            assertThatThrownBy { listener.consume(delivery, channel) }.isInstanceOf(java.io.IOException::class.java)
        } finally {
            if (connection.isOpen) connection.close()
        }
        // Consume the original redelivery first, then let the real retry TTL supply the duplicate.
        newRawConnection().use { second ->
            val channel = second.createChannel()
            val redelivery = receiveWithoutAck(channel)
            assertThat(redelivery.envelope.isRedeliver).isTrue()
            listener.consume(toMessage(redelivery), channel)
        }
        listenerContainer().start()
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertQueuesEmpty()
            assertThat(processedEventRepository.count()).isEqualTo(1)
            assertThat(notificationRequestRepository.count()).isEqualTo(1)
            Mockito.verify(handler, Mockito.times(3)).handle(
                Mockito.any(dev.climbdesk.notification.application.ReservationConfirmedNotificationCommand::class.java)
                    ?: dev.climbdesk.notification.application.ReservationConfirmedNotificationCommand(1, 1, 1, 1, 1),
            )
        }
        listenerContainer().stop()
        assertQueuesEmpty()
        assertThat(attempts.get()).isEqualTo(2)
        assertThat(notificationRequestRepository.findAll().single().sourceEventId).isEqualTo(EVENT_ID)
    }

    private fun failStore(beforeSave: () -> Unit) {
        Mockito.doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            beforeSave()
            it.callRealMethod()
        }.`when`(notificationStore).save(Mockito.any(ReservationNotificationRequest::class.java) ?: ReservationNotificationRequest(
            1, 1, 1, ReservationNotificationType.RESERVATION_CONFIRMED, ReservationNotificationStatus.READY, Instant.now(),
        ))
    }

    private fun counter(name: String, destination: String): Double =
        meterRegistry.find(name).tag("destination", destination).counter()?.count() ?: 0.0

    private fun queueGauge(name: String): Double =
        meterRegistry.get(name).tag("queue", "main").gauge().value()

    private fun savedEnvelope(): ReservationConfirmedEventEnvelopeV1 {
        val ids = savePrerequisites()
        val reservation = reservationRepository.saveAndFlush(ReservationJpaEntity(
            memberId = ids.memberId, classSessionId = ids.classSessionId, memberPassId = ids.memberPassId,
            status = ReservationStatus.CONFIRMED, reservedAt = Instant.now(),
        ))
        return envelope(EVENT_ID, reservation.id, ids)
    }

    private fun savedEnvelopeWithoutSaving(): ReservationConfirmedEventEnvelopeV1 {
        val reservation = reservationRepository.findAll().single()
        return envelope(EVENT_ID, reservation.id, ReservationIds(reservation.memberId, reservation.classSessionId, reservation.memberPassId))
    }

    private fun message(envelope: ReservationConfirmedEventEnvelopeV1) = Message(objectMapper.writeValueAsBytes(envelope), MessageProperties().apply {
        messageId = envelope.eventId.toString()
        type = "reservation.confirmed"
        contentType = MessageProperties.CONTENT_TYPE_JSON
        deliveryMode = MessageDeliveryMode.PERSISTENT
        setHeader("x-schema-version", 1)
        setHeader("x-producer", "climbdesk")
    })

    private fun sendMain(message: Message) {
        rabbitTemplate.send(RabbitMqTopology.MAIN_EXCHANGE, RabbitMqTopology.MAIN_ROUTING_KEY, message)
    }

    private fun toMessage(delivery: GetResponse): Message = Message(delivery.body, MessageProperties().apply {
        messageId = delivery.props.messageId
        type = delivery.props.type
        contentType = delivery.props.contentType
        deliveryTag = delivery.envelope.deliveryTag
        receivedExchange = delivery.envelope.exchange
        receivedRoutingKey = delivery.envelope.routingKey
        consumerQueue = RabbitMqTopology.MAIN_QUEUE
        delivery.props.headers?.forEach { (key, value) -> setHeader(key, value) }
    })

    private fun assertPreserved(original: Message, actual: Message, count: Int) {
        assertThat(actual.body).isEqualTo(original.body)
        assertThat(actual.messageProperties.messageId).isEqualTo(original.messageProperties.messageId)
        assertThat(actual.messageProperties.type).isEqualTo(original.messageProperties.type)
        assertThat(actual.messageProperties.contentType).isEqualTo(MessageProperties.CONTENT_TYPE_JSON)
        // Received messages expose delivery mode through receivedDeliveryMode.
        assertThat(actual.messageProperties.receivedDeliveryMode ?: actual.messageProperties.deliveryMode).isEqualTo(MessageDeliveryMode.PERSISTENT)
        val headers = actual.messageProperties.headers
        assertThat(headers["x-schema-version"]).isEqualTo(1)
        assertThat(headers["x-producer"].toString()).isEqualTo("climbdesk")
        assertThat(headers["x-retry-count"]).isEqualTo(count)
        assertThat(headers["x-original-exchange"].toString()).isEqualTo(RabbitMqTopology.MAIN_EXCHANGE)
        assertThat(headers["x-original-routing-key"].toString()).isEqualTo(RabbitMqTopology.MAIN_ROUTING_KEY)
        assertThat(headers["x-original-queue"].toString()).isEqualTo(RabbitMqTopology.MAIN_QUEUE)
        assertThat(headers["x-error-summary"].toString().length).isLessThanOrEqualTo(ReservationNotificationFailureRouter.MAX_ERROR_LENGTH)
    }

    private fun retryBinding() = Binding(
        RabbitMqTopology.RETRY_QUEUE_5S, Binding.DestinationType.QUEUE,
        RabbitMqTopology.RETRY_EXCHANGE, RabbitMqTopology.RETRY_QUEUE_5S, null,
    )

    private fun awaitCount(queue: String, expected: Int) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(checkNotNull(rabbitAdmin.getQueueInfo(queue)).messageCount).isEqualTo(expected)
        }
    }

    private fun assertQueuesEmpty() {
        allQueues.forEach { assertThat(checkNotNull(rabbitAdmin.getQueueInfo(it)).messageCount).describedAs(it).isZero() }
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
            registry.add("climbdesk.messaging.rabbitmq.observability.management-base-url") {
                "http://${rabbitMq.host}:${rabbitMq.getMappedPort(15672)}"
            }
        }
    }
}
