package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import dev.climbdesk.event.application.OutboundMessage
import dev.climbdesk.event.application.OutboundMessagePublisher
import dev.climbdesk.event.application.PublishResult
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RabbitOutboundMessagePublisher(
    private val rabbitTemplate: RabbitTemplate,
) : OutboundMessagePublisher {
    override fun publish(
        message: OutboundMessage,
        confirmTimeout: Duration,
    ): PublishResult {
        val correlationData = CorrelationData(message.messageId)
        val rabbitMessage = Message(message.body, messageProperties(message))

        return try {
            rabbitTemplate.send(
                RabbitMqTopology.MAIN_EXCHANGE,
                RabbitMqTopology.MAIN_ROUTING_KEY,
                rabbitMessage,
                correlationData,
            )
            val confirm = correlationData.future.get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS)
            val returned = correlationData.returned
            when {
                returned != null ->
                    PublishResult.Failure(
                        "RabbitMQ mandatory return: code=${returned.replyCode}, " +
                            "text=${returned.replyText}, exchange=${returned.exchange}, " +
                            "routingKey=${returned.routingKey}",
                    )
                !confirm.isAck ->
                    PublishResult.Failure(
                        "RabbitMQ publisher confirm NACK: ${confirm.reason}",
                    )
                else -> PublishResult.Success
            }
        } catch (exception: TimeoutException) {
            PublishResult.Failure("RabbitMQ publisher confirm timeout after ${confirmTimeout.toMillis()}ms")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            PublishResult.Failure("RabbitMQ publisher confirm wait interrupted")
        } catch (exception: Exception) {
            PublishResult.Failure("RabbitMQ publish failed: ${exception.javaClass.simpleName}")
        }
    }

    private fun messageProperties(message: OutboundMessage): MessageProperties =
        MessageProperties().apply {
            messageId = message.messageId
            type = message.eventType
            contentType = MessageProperties.CONTENT_TYPE_JSON
            deliveryMode = MessageDeliveryMode.PERSISTENT
            setHeader(SCHEMA_VERSION_HEADER, message.schemaVersion)
            setHeader(PRODUCER_HEADER, message.producer)
        }

    private companion object {
        const val SCHEMA_VERSION_HEADER = "x-schema-version"
        const val PRODUCER_HEADER = "x-producer"
    }
}
