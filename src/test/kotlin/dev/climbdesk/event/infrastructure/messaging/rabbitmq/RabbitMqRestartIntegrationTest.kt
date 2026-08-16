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
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.messaging.rabbitmq.enabled=true",
        "climbdesk.messaging.rabbitmq.publisher-enabled=false",
        "climbdesk.messaging.rabbitmq.listener-enabled=false",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///rabbitmq-restart",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
class RabbitMqRestartIntegrationTest @Autowired constructor(
    private val rabbitAdmin: RabbitAdmin,
    private val rabbitTemplate: RabbitTemplate,
    private val connectionFactory: CachingConnectionFactory,
    private val objectMapper: ObjectMapper,
) {
    @BeforeEach
    fun setUp() {
        purgeQueues()
    }

    @AfterEach
    fun tearDown() {
        ensureRabbitMqRunning()
        connectionFactory.resetConnection()
        purgeQueues()
    }

    @Test
    fun `durable production topology and persistent message survive the same broker restart`() {
        val correlationData = CorrelationData("before-restart")
        rabbitTemplate.send(
            RabbitMqTopology.MAIN_EXCHANGE,
            RabbitMqTopology.MAIN_ROUTING_KEY,
            persistentMessage("before-restart"),
            correlationData,
        )
        assertThat(correlationData.future.get(5, TimeUnit.SECONDS).isAck).isTrue()
        assertThat(correlationData.returned).isNull()

        val containerId = rabbitMq.containerId
        val nodePid = checkNotNull(rabbitMqNodePid())
        try {
            restartRabbitMqNode(nodePid)
            connectionFactory.resetConnection()
            assertThat(rabbitMq.containerId).isEqualTo(containerId)

            assertDurableTopologyFromBrokerManagementApi()
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted {
                assertThat(queue(RabbitMqTopology.MAIN_QUEUE).path("messages_ready").asInt(-1)).isEqualTo(1)
            }

            val retainedMessage = rabbitTemplate.receive(RabbitMqTopology.MAIN_QUEUE, 5_000)
            assertThat(retainedMessage).isNotNull
            assertThat(retainedMessage!!.body.toString(StandardCharsets.UTF_8)).isEqualTo("before-restart")
            assertThat(retainedMessage.messageProperties.receivedDeliveryMode)
                .isEqualTo(MessageDeliveryMode.PERSISTENT)
        } finally {
            ensureRabbitMqRunning()
            connectionFactory.resetConnection()
        }
    }

    private fun assertDurableTopologyFromBrokerManagementApi() {
        assertDurableExchange(RabbitMqTopology.MAIN_EXCHANGE, "topic")
        assertDurableExchange(RabbitMqTopology.RETRY_EXCHANGE, "direct")
        assertDurableExchange(RabbitMqTopology.DEAD_LETTER_EXCHANGE, "direct")

        val mainQueue = queue(RabbitMqTopology.MAIN_QUEUE)
        assertThat(mainQueue["durable"].booleanValue()).isTrue()
        assertThat(mainQueue["arguments"]["x-dead-letter-exchange"].textValue())
            .isEqualTo(RabbitMqTopology.DEAD_LETTER_EXCHANGE)
        assertThat(mainQueue["arguments"]["x-dead-letter-routing-key"].textValue())
            .isEqualTo(RabbitMqTopology.DEAD_LETTER_QUEUE)
        assertBinding(
            RabbitMqTopology.MAIN_EXCHANGE,
            RabbitMqTopology.MAIN_QUEUE,
            RabbitMqTopology.MAIN_ROUTING_KEY,
        )

        RabbitMqTopology.retryQueues.forEach { retry ->
            val retryQueue = queue(retry.name)
            assertThat(retryQueue["durable"].booleanValue()).isTrue()
            assertThat(retryQueue["arguments"]["x-message-ttl"].intValue()).isEqualTo(retry.ttl)
            assertThat(retryQueue["arguments"]["x-dead-letter-exchange"].textValue())
                .isEqualTo(RabbitMqTopology.MAIN_EXCHANGE)
            assertThat(retryQueue["arguments"]["x-dead-letter-routing-key"].textValue())
                .isEqualTo(RabbitMqTopology.MAIN_ROUTING_KEY)
            assertBinding(RabbitMqTopology.RETRY_EXCHANGE, retry.name, retry.name)
        }

        assertThat(queue(RabbitMqTopology.DEAD_LETTER_QUEUE)["durable"].booleanValue()).isTrue()
        assertBinding(
            RabbitMqTopology.DEAD_LETTER_EXCHANGE,
            RabbitMqTopology.DEAD_LETTER_QUEUE,
            RabbitMqTopology.DEAD_LETTER_QUEUE,
        )
    }

    private fun assertDurableExchange(
        name: String,
        type: String,
    ) {
        val exchange = exchange(name)
        assertThat(exchange["type"].textValue()).isEqualTo(type)
        assertThat(exchange["durable"].booleanValue()).isTrue()
    }

    private fun assertBinding(
        exchange: String,
        queue: String,
        routingKey: String,
    ) {
        assertThat(bindings(exchange, queue).map { it["routing_key"].textValue() })
            .contains(routingKey)
    }

    private fun restartRabbitMqNode(previousPid: String) {
        val result = rabbitMq.execInContainer("rabbitmqctl", "shutdown")
        assertThat(result.exitCode).isZero()
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until {
            rabbitMqNodePid()?.let { it != previousPid } == true
        }
        ensureRabbitMqRunning()
    }

    private fun ensureRabbitMqRunning() {
        if (!rabbitMqContainerIsRunning()) {
            rabbitMq.dockerClient.startContainerCmd(rabbitMq.containerId).exec()
        }
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until {
            rabbitMqContainerIsRunning()
        }
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until {
            rabbitMqNodeResponds()
        }
        if (!rabbitMqApplicationIsRunning()) {
            val result = rabbitMq.execInContainer("rabbitmqctl", "start_app")
            assertThat(result.exitCode).isZero()
        }
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until {
            rabbitMqApplicationIsRunning()
        }
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until {
            managementApiIsReady()
        }
    }

    private fun rabbitMqContainerIsRunning(): Boolean =
        rabbitMq.dockerClient.inspectContainerCmd(rabbitMq.containerId).exec().state.running == true

    private fun rabbitMqNodePid(): String? =
        runCatching {
            rabbitMq.execInContainer("pidof", "beam.smp").stdout.trim().takeIf(String::isNotEmpty)
        }.getOrNull()

    private fun rabbitMqNodeResponds(): Boolean =
        runCatching {
            rabbitMq.execInContainer("rabbitmq-diagnostics", "-q", "ping").exitCode == 0
        }.getOrDefault(false)

    private fun rabbitMqApplicationIsRunning(): Boolean =
        runCatching {
            rabbitMq.execInContainer("rabbitmq-diagnostics", "-q", "check_running").exitCode == 0
        }.getOrDefault(false)

    private fun managementApiIsReady(): Boolean =
        runCatching { managementResponse("/api/overview").statusCode() == 200 }.getOrDefault(false)

    private fun exchange(name: String): JsonNode = management("/api/exchanges/%2F/$name")

    private fun queue(name: String): JsonNode = management("/api/queues/%2F/$name")

    private fun bindings(
        exchange: String,
        queue: String,
    ): JsonNode = management("/api/bindings/%2F/e/$exchange/q/$queue")

    private fun management(path: String): JsonNode {
        val response = managementResponse(path)
        assertThat(response.statusCode()).isEqualTo(200)
        return objectMapper.readTree(response.body())
    }

    private fun managementResponse(path: String): HttpResponse<String> {
        val credentials = "${rabbitMq.adminUsername}:${rabbitMq.adminPassword}"
        val authorization = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
        val request =
            HttpRequest.newBuilder()
                .uri(URI("http://${rabbitMq.host}:${rabbitMq.httpPort}$path"))
                .timeout(Duration.ofSeconds(2))
                .header("Authorization", "Basic $authorization")
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun persistentMessage(body: String): Message =
        Message(
            body.toByteArray(StandardCharsets.UTF_8),
            MessageProperties().apply { deliveryMode = MessageDeliveryMode.PERSISTENT },
        )

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
                .withCommand(
                    "sh",
                    "-c",
                    "while true; do docker-entrypoint.sh rabbitmq-server; done",
                )

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
