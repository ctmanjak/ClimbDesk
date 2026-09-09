package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import dev.climbdesk.event.application.FailureRepublishDestination
import dev.climbdesk.event.application.MessagingObservation
import dev.climbdesk.event.application.NoOpMessagingObservation
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RabbitNotificationFailurePublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val confirmTimeout: Duration,
    private val observation: MessagingObservation = NoOpMessagingObservation,
) {
    init {
        val factory = rabbitTemplate.connectionFactory
        require(factory is CachingConnectionFactory) { "Failure publisher requires CachingConnectionFactory" }
        require(factory.isPublisherConfirms && !factory.isSimplePublisherConfirms) {
            "Failure publisher requires correlated confirms"
        }
        require(factory.isPublisherReturns) { "Failure publisher requires publisher returns" }
        require(rabbitTemplate.isMandatoryFor(Message(ByteArray(0))) == true) {
            "Failure publisher requires mandatory publishing"
        }
        require(!confirmTimeout.isNegative && !confirmTimeout.isZero) { "Confirm timeout must be positive" }
    }

    fun publish(route: ReservationNotificationFailureRoute) {
        val destination =
            if (route.exchange == RabbitMqTopology.RETRY_EXCHANGE) {
                FailureRepublishDestination.RETRY
            } else {
                FailureRepublishDestination.DLQ
            }
        // Each attempt needs its own correlation even when multiple deliveries share a messageId.
        val correlation = CorrelationData()
        try {
            rabbitTemplate.send(route.exchange, route.routingKey, route.message, correlation)
            val confirm = correlation.future.get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS)
            when {
                correlation.returned != null -> throw FailureRepublishException(FailureRepublishReason.MANDATORY_RETURN)
                !confirm.isAck -> throw FailureRepublishException(FailureRepublishReason.NACK)
            }
        } catch (exception: Exception) {
            observation.failureRepublishFailed(destination)
            logger.warn(
                "Reservation notification failure republish unconfirmed: eventId={} destination={} reason={}",
                safeEventId(route),
                destination,
                failureReason(exception, correlation),
            )
            when (exception) {
                is FailureRepublishException -> throw exception
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    throw FailureRepublishException(FailureRepublishReason.INTERRUPTED)
                }
                is TimeoutException ->
                    throw FailureRepublishException(
                        if (correlation.returned != null) {
                            FailureRepublishReason.MANDATORY_RETURN
                        } else {
                            FailureRepublishReason.TIMEOUT
                        },
                    )
                // Do not expose broker/client exception text or a potentially fatal nested cause to the container.
                else -> throw FailureRepublishException(FailureRepublishReason.SEND_OR_CONNECTION)
            }
        }
        observation.failureRepublishConfirmed(destination)
        logger.info(
            "Reservation notification failure republish confirmed: eventId={} destination={}",
            safeEventId(route),
            destination,
        )
    }

    private fun safeEventId(route: ReservationNotificationFailureRoute): String =
        route.message.messageProperties.messageId?.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: "unavailable"

    private fun failureReason(exception: Exception, correlation: CorrelationData): FailureRepublishReason =
        when (exception) {
            is FailureRepublishException -> exception.reason
            is InterruptedException -> FailureRepublishReason.INTERRUPTED
            is TimeoutException ->
                if (correlation.returned != null) FailureRepublishReason.MANDATORY_RETURN else FailureRepublishReason.TIMEOUT
            else -> FailureRepublishReason.SEND_OR_CONNECTION
        }

    private companion object {
        val logger = LoggerFactory.getLogger(RabbitNotificationFailurePublisher::class.java)
    }
}

enum class FailureRepublishReason { NACK, TIMEOUT, MANDATORY_RETURN, INTERRUPTED, SEND_OR_CONNECTION }

class FailureRepublishException(val reason: FailureRepublishReason) : RuntimeException("Failure republish unconfirmed: $reason")
