package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RabbitNotificationFailurePublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val confirmTimeout: Duration,
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
        // Each attempt needs its own correlation even when multiple deliveries share a messageId.
        val correlation = CorrelationData()
        try {
            rabbitTemplate.send(route.exchange, route.routingKey, route.message, correlation)
            val confirm = correlation.future.get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS)
            when {
                correlation.returned != null -> throw FailureRepublishException(FailureRepublishReason.MANDATORY_RETURN)
                !confirm.isAck -> throw FailureRepublishException(FailureRepublishReason.NACK)
            }
        } catch (exception: FailureRepublishException) {
            throw exception
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FailureRepublishException(FailureRepublishReason.INTERRUPTED)
        } catch (_: TimeoutException) {
            throw FailureRepublishException(
                if (correlation.returned != null) FailureRepublishReason.MANDATORY_RETURN else FailureRepublishReason.TIMEOUT,
            )
        } catch (_: Exception) {
            // Do not expose broker/client exception text or a potentially fatal nested cause to the container.
            throw FailureRepublishException(FailureRepublishReason.SEND_OR_CONNECTION)
        }
    }
}

enum class FailureRepublishReason { NACK, TIMEOUT, MANDATORY_RETURN, INTERRUPTED, SEND_OR_CONNECTION }

class FailureRepublishException(val reason: FailureRepublishReason) : RuntimeException("Failure republish unconfirmed: $reason")
