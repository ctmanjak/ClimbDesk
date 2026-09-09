package dev.climbdesk.event.infrastructure.observability

import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.AMQP
import dev.climbdesk.event.infrastructure.messaging.rabbitmq.RabbitMqTopology
import dev.climbdesk.event.infrastructure.messaging.rabbitmq.DlqSingleMessageReplayCommand
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.time.Duration

@Testcontainers(disabledWithoutDocker = true)
class RabbitMqQueueMetricsIntegrationTest {
    @Test
    fun `management metrics distinguish prefetch saturation from cumulative failures and record drain`() {
        connectionFactory().newConnection().use { connection ->
            connection.createChannel().use { channel ->
                allQueues.forEach { channel.queueDeclare(it, true, false, false, emptyMap()) }
                repeat(10) { channel.basicPublish("", RabbitMqTopology.MAIN_QUEUE, null, "{}".toByteArray()) }
                repeat(10) { assertThat(channel.basicGet(RabbitMqTopology.MAIN_QUEUE, false)).isNotNull() }

                val registry = SimpleMeterRegistry()
                val metrics = queueMetrics()
                metrics.bindTo(registry)
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted {
                    metrics.refresh()
                    assertThat(gauge(registry, "climbdesk.messaging.rabbitmq.queue.depth", "main")).isEqualTo(10.0)
                    assertThat(gauge(registry, "climbdesk.messaging.rabbitmq.queue.unacknowledged", "main")).isEqualTo(10.0)
                }

                channel.basicAck(10, true)
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted {
                    metrics.refresh()
                    assertThat(gauge(registry, "climbdesk.messaging.rabbitmq.queue.depth", "main")).isZero()
                    assertThat(gauge(registry, "climbdesk.messaging.rabbitmq.queue.unacknowledged", "main")).isZero()
                    assertThat(registry.get("climbdesk.messaging.rabbitmq.main.backlog.drain").timer().count()).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun `single DLQ replay preserves event id and acknowledges original only after confirmed routing`() {
        connectionFactory().newConnection().use { connection ->
            connection.createChannel().use { channel ->
                channel.exchangeDeclare(RabbitMqTopology.MAIN_EXCHANGE, "topic", true)
                channel.queueDeclare(RabbitMqTopology.MAIN_QUEUE, true, false, false, emptyMap())
                channel.queueBind(
                    RabbitMqTopology.MAIN_QUEUE,
                    RabbitMqTopology.MAIN_EXCHANGE,
                    RabbitMqTopology.MAIN_ROUTING_KEY,
                )
                channel.queueDeclare(RabbitMqTopology.DEAD_LETTER_QUEUE, true, false, false, emptyMap())
                val properties = AMQP.BasicProperties.Builder()
                    .messageId("701")
                    .type("reservation.confirmed")
                    .deliveryMode(2)
                    .headers(mapOf("x-retry-count" to 3, "x-failure-category" to "UNKNOWN"))
                    .build()
                channel.basicPublish("", RabbitMqTopology.DEAD_LETTER_QUEUE, properties, "{}".toByteArray())

                val original = checkNotNull(channel.basicGet(RabbitMqTopology.DEAD_LETTER_QUEUE, false))
                val publisherFactory = CachingConnectionFactory(rabbitMq.host, rabbitMq.amqpPort).apply {
                    setUsername(rabbitMq.adminUsername)
                    setPassword(rabbitMq.adminPassword)
                    setVirtualHost("/")
                    setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED)
                    isPublisherReturns = true
                }
                try {
                    val template = RabbitTemplate(publisherFactory).apply { setMandatory(true) }
                    DlqSingleMessageReplayCommand.replayAndAcknowledge(channel, original, template)
                } finally {
                    publisherFactory.destroy()
                }

                assertThat(channel.queueDeclarePassive(RabbitMqTopology.DEAD_LETTER_QUEUE).messageCount).isZero()
                val replay = checkNotNull(channel.basicGet(RabbitMqTopology.MAIN_QUEUE, true))
                assertThat(replay.props.messageId).isEqualTo("701")
                assertThat(replay.body).isEqualTo("{}".toByteArray())
                assertThat(replay.props.headers["x-retry-count"]).isEqualTo(3)
                assertThat(replay.props.headers["x-failure-category"].toString()).isEqualTo("UNKNOWN")
            }
        }
    }

    private fun queueMetrics() = RabbitMqQueueMetrics(
        managementBaseUrl = "http://${rabbitMq.host}:${rabbitMq.getMappedPort(15672)}",
        username = rabbitMq.adminUsername,
        password = rabbitMq.adminPassword,
        virtualHost = "/",
        objectMapper = ObjectMapper(),
        requestTimeout = Duration.ofSeconds(2),
    )

    private fun connectionFactory() = ConnectionFactory().apply {
        host = rabbitMq.host
        port = rabbitMq.amqpPort
        username = rabbitMq.adminUsername
        password = rabbitMq.adminPassword
        virtualHost = "/"
    }

    private fun gauge(registry: SimpleMeterRegistry, name: String, queue: String): Double =
        registry.get(name).tag("queue", queue).gauge().value()

    companion object {
        private val allQueues = listOf(
            RabbitMqTopology.MAIN_QUEUE,
            RabbitMqTopology.RETRY_QUEUE_5S,
            RabbitMqTopology.RETRY_QUEUE_30S,
            RabbitMqTopology.RETRY_QUEUE_2M,
            RabbitMqTopology.DEAD_LETTER_QUEUE,
        )

        @Container
        @JvmStatic
        val rabbitMq = RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"))
    }
}
