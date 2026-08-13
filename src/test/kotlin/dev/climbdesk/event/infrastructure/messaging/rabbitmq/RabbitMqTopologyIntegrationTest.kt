package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.amqp.RabbitProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.messaging.rabbitmq.enabled=true",
        "climbdesk.messaging.rabbitmq.publisher-enabled=false",
        "climbdesk.messaging.rabbitmq.listener-enabled=false",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///rabbitmq-topology",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
class RabbitMqTopologyIntegrationTest @Autowired constructor(
    private val rabbitAdmin: RabbitAdmin,
    private val rabbitTemplate: RabbitTemplate,
    private val connectionFactory: CachingConnectionFactory,
    private val rabbitProperties: RabbitProperties,
    private val objectMapper: ObjectMapper,
    private val restTemplate: TestRestTemplate,
    @param:LocalServerPort private val serverPort: Int,
) {
    @BeforeEach
    fun setUp() {
        purgeQueues()
    }

    @AfterEach
    fun tearDown() {
        purgeQueues()
    }

    @Test
    fun `application declares durable main retry and dead letter topology idempotently`() {
        rabbitAdmin.initialize()
        rabbitAdmin.initialize()

        val mainExchange = exchange(RabbitMqTopology.MAIN_EXCHANGE)
        assertThat(mainExchange["type"].textValue()).isEqualTo("topic")
        assertThat(mainExchange["durable"].booleanValue()).isTrue()

        val retryExchange = exchange(RabbitMqTopology.RETRY_EXCHANGE)
        assertThat(retryExchange["type"].textValue()).isEqualTo("direct")
        assertThat(retryExchange["durable"].booleanValue()).isTrue()

        val deadLetterExchange = exchange(RabbitMqTopology.DEAD_LETTER_EXCHANGE)
        assertThat(deadLetterExchange["type"].textValue()).isEqualTo("direct")
        assertThat(deadLetterExchange["durable"].booleanValue()).isTrue()

        assertDurableQueue(RabbitMqTopology.MAIN_QUEUE)
        assertBinding(
            RabbitMqTopology.MAIN_EXCHANGE,
            RabbitMqTopology.MAIN_QUEUE,
            RabbitMqTopology.MAIN_ROUTING_KEY,
        )

        RabbitMqTopology.retryQueues.forEach { retry ->
            val queue = queue(retry.name)
            assertThat(queue["durable"].booleanValue()).isTrue()
            assertThat(queue["arguments"]["x-message-ttl"].intValue()).isEqualTo(retry.ttl)
            assertThat(queue["arguments"]["x-dead-letter-exchange"].textValue())
                .isEqualTo(RabbitMqTopology.MAIN_EXCHANGE)
            assertThat(queue["arguments"]["x-dead-letter-routing-key"].textValue())
                .isEqualTo(RabbitMqTopology.MAIN_ROUTING_KEY)
            assertBinding(RabbitMqTopology.RETRY_EXCHANGE, retry.name, retry.name)
        }

        assertDurableQueue(RabbitMqTopology.DEAD_LETTER_QUEUE)
        assertBinding(
            RabbitMqTopology.DEAD_LETTER_EXCHANGE,
            RabbitMqTopology.DEAD_LETTER_QUEUE,
            RabbitMqTopology.DEAD_LETTER_QUEUE,
        )
    }

    @Test
    fun `main exchange routes only the reservation confirmed routing key`() {
        rabbitTemplate.convertAndSend(
            RabbitMqTopology.MAIN_EXCHANGE,
            RabbitMqTopology.MAIN_ROUTING_KEY,
            "routed",
        )

        assertThat(receive(RabbitMqTopology.MAIN_QUEUE).body.toString(StandardCharsets.UTF_8))
            .isEqualTo("routed")

        val correlationData = CorrelationData("wrong-main-routing-key")
        rabbitTemplate.convertAndSend(
            RabbitMqTopology.MAIN_EXCHANGE,
            "reservation.unknown.v1",
            "unroutable",
            correlationData,
        )

        assertThat(confirm(correlationData).isAck).isTrue()
        assertThat(correlationData.returned).isNotNull
        assertThat(rabbitTemplate.receive(RabbitMqTopology.MAIN_QUEUE, 200)).isNull()
    }

    @Test
    fun `publisher confirm ack and mandatory return are distinct outcomes`() {
        val routed = CorrelationData("routed-confirm")
        rabbitTemplate.convertAndSend(
            RabbitMqTopology.MAIN_EXCHANGE,
            RabbitMqTopology.MAIN_ROUTING_KEY,
            "confirmed",
            routed,
        )

        assertThat(confirm(routed).isAck).isTrue()
        assertThat(routed.returned).isNull()

        val unroutable = CorrelationData("unroutable-confirm")
        rabbitTemplate.convertAndSend(
            RabbitMqTopology.MAIN_EXCHANGE,
            "reservation.unroutable.v1",
            "returned",
            unroutable,
        )

        assertThat(confirm(unroutable).isAck).isTrue()
        assertThat(unroutable.returned).isNotNull
        assertThat(unroutable.returned!!.exchange).isEqualTo(RabbitMqTopology.MAIN_EXCHANGE)
        assertThat(unroutable.returned!!.routingKey).isEqualTo("reservation.unroutable.v1")
    }

    @Test
    fun `default message delivery mode is persistent`() {
        rabbitTemplate.convertAndSend(
            RabbitMqTopology.MAIN_EXCHANGE,
            RabbitMqTopology.MAIN_ROUTING_KEY,
            "persistent",
        )

        assertThat(receive(RabbitMqTopology.MAIN_QUEUE).messageProperties.receivedDeliveryMode)
            .isEqualTo(MessageDeliveryMode.PERSISTENT)
    }

    @Test
    fun `short retry queue returns its message to the main queue after TTL`() {
        rabbitTemplate.convertAndSend(
            RabbitMqTopology.RETRY_EXCHANGE,
            RabbitMqTopology.RETRY_QUEUE_5S,
            "retry",
        )

        val received = AtomicReference<Message>()
        await().atMost(Duration.ofSeconds(12)).pollInterval(Duration.ofMillis(200)).untilAsserted {
            rabbitTemplate.receive(RabbitMqTopology.MAIN_QUEUE)?.let(received::set)
            assertThat(received.get()).isNotNull
        }

        assertThat(received.get().body.toString(StandardCharsets.UTF_8)).isEqualTo("retry")
    }

    @Test
    fun `dead letter exchange routes to the dead letter queue`() {
        rabbitTemplate.convertAndSend(
            RabbitMqTopology.DEAD_LETTER_EXCHANGE,
            RabbitMqTopology.DEAD_LETTER_QUEUE,
            "dead-letter",
        )

        assertThat(receive(RabbitMqTopology.DEAD_LETTER_QUEUE).body.toString(StandardCharsets.UTF_8))
            .isEqualTo("dead-letter")
    }

    @Test
    fun `publisher and listener reliability settings and RabbitMQ health are active`() {
        assertThat(connectionFactory.isPublisherConfirms).isTrue()
        assertThat(connectionFactory.isPublisherReturns).isTrue()
        assertThat(rabbitTemplate.isMandatoryFor(Message(ByteArray(0)))).isTrue()
        assertThat(rabbitProperties.listener.simple.acknowledgeMode.name).isEqualTo("MANUAL")
        assertThat(rabbitProperties.listener.simple.defaultRequeueRejected).isFalse()
        assertThat(rabbitProperties.listener.simple.prefetch).isEqualTo(10)
        assertThat(rabbitProperties.listener.simple.concurrency).isEqualTo(1)

        val response = restTemplate.getForEntity("http://localhost:$serverPort/actuator/health", JsonNode::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["components"]["rabbit"]["status"].textValue()).isEqualTo("UP")
    }

    private fun receive(queue: String): Message =
        await().atMost(Duration.ofSeconds(5)).until(
            { rabbitTemplate.receive(queue) },
            { it != null },
        ) ?: error("No message received from $queue")

    private fun confirm(correlationData: CorrelationData): CorrelationData.Confirm =
        correlationData.future.get(5, TimeUnit.SECONDS)

    private fun assertDurableQueue(name: String) {
        assertThat(queue(name)["durable"].booleanValue()).isTrue()
    }

    private fun assertBinding(
        exchange: String,
        queue: String,
        routingKey: String,
    ) {
        assertThat(bindings(exchange, queue).map { it["routing_key"].textValue() })
            .contains(routingKey)
    }

    private fun exchange(name: String): JsonNode = management("/api/exchanges/%2F/$name")

    private fun queue(name: String): JsonNode = management("/api/queues/%2F/$name")

    private fun bindings(
        exchange: String,
        queue: String,
    ): JsonNode = management("/api/bindings/%2F/e/$exchange/q/$queue")

    private fun management(path: String): JsonNode {
        val credentials = "${rabbitMq.adminUsername}:${rabbitMq.adminPassword}"
        val authorization = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
        val request =
            HttpRequest.newBuilder()
                .uri(URI("http://${rabbitMq.host}:${rabbitMq.httpPort}$path"))
                .header("Authorization", "Basic $authorization")
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(200)
        return objectMapper.readTree(response.body())
    }

    private fun purgeQueues() {
        allQueues.forEach { rabbitAdmin.purgeQueue(it, false) }
    }

    companion object {
        private val httpClient: HttpClient = HttpClient.newHttpClient()
        private val allQueues =
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
        fun registerContainerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.rabbitmq.host", rabbitMq::getHost)
            registry.add("spring.rabbitmq.port", rabbitMq::getAmqpPort)
            registry.add("spring.rabbitmq.username", rabbitMq::getAdminUsername)
            registry.add("spring.rabbitmq.password", rabbitMq::getAdminPassword)
            registry.add("spring.rabbitmq.virtual-host") { "/" }
        }
    }
}
