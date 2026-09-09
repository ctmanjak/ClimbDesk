package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object DlqSingleMessageReplayCommand {
    @JvmStatic
    fun main(args: Array<String>) {
        val action = environment("CLIMBDESK_DLQ_REPLAY_ACTION", "inspect")
        val expectedEventId = System.getenv("CLIMBDESK_DLQ_REPLAY_EVENT_ID")?.toLongOrNull()
        require(action == "inspect" || action == "replay") { "Action must be inspect or replay" }
        if (action == "replay") {
            require(expectedEventId != null && expectedEventId > 0) { "A positive CLIMBDESK_DLQ_REPLAY_EVENT_ID is required" }
            require(System.getenv("CLIMBDESK_DLQ_REPLAY_CONFIRM") == expectedEventId.toString()) {
                "CLIMBDESK_DLQ_REPLAY_CONFIRM must exactly match the event id"
            }
        }

        val settings = connectionSettings()
        settings.rawFactory().newConnection("climbdesk-dlq-single-message-replay").use { connection ->
            connection.createChannel().use { channel ->
                val delivery = channel.basicGet(RabbitMqTopology.DEAD_LETTER_QUEUE, false)
                    ?: error("DLQ is empty")
                val eventId = validatedEventId(delivery)
                printInvestigation(delivery, eventId)
                if (action == "inspect") {
                    channel.basicNack(delivery.envelope.deliveryTag, false, true)
                    println("result=INSPECTED_AND_REQUEUED")
                } else {
                    require(eventId == expectedEventId) {
                        "The DLQ head eventId does not match the requested event id; original remains in the DLQ"
                    }
                    val publisherFactory = settings.springFactory()
                    try {
                        replayAndAcknowledge(channel, delivery, RabbitTemplate(publisherFactory).apply { setMandatory(true) })
                    } finally {
                        publisherFactory.destroy()
                    }
                    println("result=REPLAY_CONFIRMED_AND_DLQ_ACKNOWLEDGED eventId=$eventId")
                }
            }
        }
    }

    internal fun replayAndAcknowledge(
        channel: Channel,
        delivery: GetResponse,
        rabbitTemplate: RabbitTemplate,
    ) {
        val deliveryTag = delivery.envelope.deliveryTag
        val correlation = CorrelationData()
        val messageProperties = DefaultMessagePropertiesConverter().toMessageProperties(
            delivery.props,
            delivery.envelope,
            StandardCharsets.UTF_8.name(),
        )
        try {
            rabbitTemplate.send(
                RabbitMqTopology.MAIN_EXCHANGE,
                RabbitMqTopology.MAIN_ROUTING_KEY,
                Message(delivery.body, messageProperties),
                correlation,
            )
            val confirm = correlation.future.get(CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            check(confirm.isAck) { "Replay received publisher NACK" }
            check(correlation.returned == null) { "Replay was returned as unroutable" }
        } catch (exception: Exception) {
            if (channel.isOpen) {
                channel.basicNack(deliveryTag, false, true)
            }
            throw exception
        }
        channel.basicAck(deliveryTag, false)
    }

    private fun validatedEventId(delivery: GetResponse): Long =
        delivery.props.messageId?.toLongOrNull()?.takeIf { it > 0 }
            ?: error("DLQ messageId is not a valid positive eventId; original remains in the DLQ")

    private fun printInvestigation(delivery: GetResponse, eventId: Long) {
        val headers = delivery.props.headers.orEmpty()
        println("eventId=$eventId")
        println("eventType=${delivery.props.type ?: "<missing>"}")
        listOf(
            "x-schema-version",
            "x-retry-count",
            "x-failure-category",
            "x-original-exchange",
            "x-original-routing-key",
            "x-original-queue",
            "x-first-failed-at",
            "x-last-failed-at",
            "x-exception-class",
            "x-error-summary",
        ).forEach { key -> println("$key=${headers[key] ?: "<missing>"}") }
    }

    private fun connectionSettings() = ConnectionSettings(
        host = environment("CLIMBDESK_RABBITMQ_HOST", "localhost"),
        port = environment("CLIMBDESK_RABBITMQ_PORT", "5672").toInt(),
        username = environment("CLIMBDESK_RABBITMQ_USERNAME", "climbdesk"),
        password = environment("CLIMBDESK_RABBITMQ_PASSWORD", "climbdesk"),
        virtualHost = environment("CLIMBDESK_RABBITMQ_VIRTUAL_HOST", "/"),
    )

    private data class ConnectionSettings(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val virtualHost: String,
    ) {
        fun rawFactory() = ConnectionFactory().also { configure(it) }

        fun springFactory() = CachingConnectionFactory(host, port).apply {
            setUsername(username)
            setPassword(password)
            setVirtualHost(virtualHost)
            setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED)
            isPublisherReturns = true
        }

        private fun configure(factory: ConnectionFactory) {
            factory.host = host
            factory.port = port
            factory.username = username
            factory.password = password
            factory.virtualHost = virtualHost
        }
    }

    private fun environment(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    private const val CONFIRM_TIMEOUT_MILLIS = 5_000L
}
