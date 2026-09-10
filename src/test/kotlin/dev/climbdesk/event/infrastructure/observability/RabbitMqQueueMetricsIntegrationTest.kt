package dev.climbdesk.event.infrastructure.observability

import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.ConnectionFactory
import dev.climbdesk.event.infrastructure.messaging.rabbitmq.RabbitMqTopology
import dev.climbdesk.event.infrastructure.messaging.rabbitmq.DlqSingleMessageReplayCommand
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.io.IOException
import java.time.Duration

@Testcontainers(disabledWithoutDocker = true)
class RabbitMqQueueMetricsIntegrationTest {
    @BeforeEach
    fun setUp() {
        connectionFactory().newConnection().use { connection ->
            connection.createChannel().use { channel ->
                allQueues.forEach { queue ->
                    channel.queueDeclare(queue, true, false, false, emptyMap())
                    channel.queuePurge(queue)
                }
            }
        }
    }

    @Test
    fun `management metrics distinguish prefetch saturation from cumulative failures and record drain`() {
        connectionFactory().newConnection().use { connection ->
            connection.createChannel().use { channel ->
                repeat(10) { channel.basicPublish("", RabbitMqTopology.MAIN_QUEUE, null, "{}".toByteArray()) }
                val lastDeliveryTag = List(10) {
                    checkNotNull(channel.basicGet(RabbitMqTopology.MAIN_QUEUE, false)).envelope.deliveryTag
                }.last()

                val registry = SimpleMeterRegistry()
                val metrics = queueMetrics()
                metrics.bindTo(registry)
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted {
                    metrics.refresh()
                    assertThat(gauge(registry, "climbdesk.messaging.rabbitmq.queue.depth", "main")).isEqualTo(10.0)
                    assertThat(gauge(registry, "climbdesk.messaging.rabbitmq.queue.unacknowledged", "main")).isEqualTo(10.0)
                }

                channel.basicAck(lastDeliveryTag, true)
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
    fun `one unavailable queue does not hide healthy main and DLQ gauges`() {
        connectionFactory().newConnection().use { connection ->
            connection.createChannel().use { channel ->
                channel.basicPublish("", RabbitMqTopology.MAIN_QUEUE, null, "{}".toByteArray())
                channel.basicPublish("", RabbitMqTopology.DEAD_LETTER_QUEUE, null, "{}".toByteArray())
                channel.queueDelete(RabbitMqTopology.RETRY_QUEUE_5S)

                val registry = SimpleMeterRegistry()
                val metrics = queueMetrics()
                metrics.bindTo(registry)
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted {
                    metrics.refresh()
                    assertThat(gauge(registry, "climbdesk.messaging.rabbitmq.queue.depth", "main")).isEqualTo(1.0)
                    assertThat(gauge(registry, "climbdesk.messaging.rabbitmq.queue.depth", "dlq")).isEqualTo(1.0)
                    assertThat(gauge(registry, "climbdesk.messaging.rabbitmq.queue.depth", "retry_5s")).isNaN()
                }
            }
        }
    }

    @Test
    fun `DLQ replay ack failure propagates without attempting to nack`() {
        connectionFactory().newConnection().use { connection ->
            connection.createChannel().use { channel ->
                channel.exchangeDeclare(RabbitMqTopology.MAIN_EXCHANGE, "topic", true)
                channel.queueBind(
                    RabbitMqTopology.MAIN_QUEUE,
                    RabbitMqTopology.MAIN_EXCHANGE,
                    RabbitMqTopology.MAIN_ROUTING_KEY,
                )
                channel.basicPublish("", RabbitMqTopology.DEAD_LETTER_QUEUE, null, "{}".toByteArray())
                val original = checkNotNull(channel.basicGet(RabbitMqTopology.DEAD_LETTER_QUEUE, false))
                val ackFailingChannel = Mockito.spy(channel)
                Mockito.doThrow(IOException("ack failed")).`when`(ackFailingChannel)
                    .basicAck(original.envelope.deliveryTag, false)
                val publisherFactory = CachingConnectionFactory(rabbitMq.host, rabbitMq.amqpPort).apply {
                    setUsername(rabbitMq.adminUsername)
                    setPassword(rabbitMq.adminPassword)
                    setVirtualHost("/")
                    setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED)
                    isPublisherReturns = true
                }
                try {
                    val template = RabbitTemplate(publisherFactory).apply { setMandatory(true) }
                    assertThatThrownBy {
                        DlqSingleMessageReplayCommand.replayAndAcknowledge(ackFailingChannel, original, template)
                    }.isInstanceOf(IOException::class.java)
                    Mockito.verify(ackFailingChannel, Mockito.never()).basicNack(
                        Mockito.anyLong(),
                        Mockito.anyBoolean(),
                        Mockito.anyBoolean(),
                    )
                } finally {
                    publisherFactory.destroy()
                }
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
