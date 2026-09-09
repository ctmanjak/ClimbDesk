package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import java.time.Clock

class ReservationNotificationFailureRouter(private val clock: Clock) {
    fun retryCount(message: Message): Int =
        when (val count = message.messageProperties.headers[RETRY_COUNT]) {
            null -> if (message.messageProperties.headers.containsKey(RETRY_COUNT)) invalidCount() else 0
            is Byte, is Short, is Int, is Long ->
                count.toLong().takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: invalidCount()
            else -> invalidCount()
        }

    fun route(
        original: Message,
        category: ReservationNotificationFailureCategory,
        exception: Throwable,
    ): ReservationNotificationFailureRoute {
        val count = try {
            retryCount(original)
        } catch (_: InvalidReservationConfirmedMessageException) {
            null
        }
        val effectiveCategory =
            if (count == null) ReservationNotificationFailureCategory.PERMANENT_MESSAGE else category
        val retry = effectiveCategory.retryable && count != null && count < 3
        val routingKey =
            if (retry) RabbitMqTopology.retryQueues[count].name else RabbitMqTopology.DEAD_LETTER_QUEUE
        val source = original.messageProperties
        val now = clock.instant().toString()
        val properties = MessageProperties().apply {
            messageId = source.messageId
            type = source.type
            contentType = MessageProperties.CONTENT_TYPE_JSON
            deliveryMode = MessageDeliveryMode.PERSISTENT
            // Copy only the contract and investigation headers, not arbitrary payload-like headers.
            listOf("x-schema-version", "x-producer", RETRY_COUNT).forEach { key ->
                source.headers[key]?.let { setHeader(key, it) }
            }
            if (count != null) setHeader(RETRY_COUNT, if (retry) count + 1 else count)
            setHeader(
                "x-original-exchange",
                source.headers["x-original-exchange"] ?: source.receivedExchange ?: RabbitMqTopology.MAIN_EXCHANGE,
            )
            setHeader(
                "x-original-routing-key",
                source.headers["x-original-routing-key"] ?: source.receivedRoutingKey ?: RabbitMqTopology.MAIN_ROUTING_KEY,
            )
            setHeader(
                "x-original-queue",
                source.headers["x-original-queue"] ?: source.consumerQueue ?: RabbitMqTopology.MAIN_QUEUE,
            )
            setHeader("x-first-failed-at", source.headers["x-first-failed-at"] ?: now)
            setHeader("x-last-failed-at", now)
            setHeader("x-failure-category", effectiveCategory.name)
            setHeader(
                "x-exception-class",
                exception.javaClass.simpleName.filter { it.isLetterOrDigit() || it == '_' }.take(MAX_ERROR_LENGTH),
            )
            // Fixed summaries never include exception messages, SQL, payloads, or stack traces.
            setHeader("x-error-summary", effectiveCategory.summary.take(MAX_ERROR_LENGTH))
        }
        return ReservationNotificationFailureRoute(
            exchange = if (retry) RabbitMqTopology.RETRY_EXCHANGE else RabbitMqTopology.DEAD_LETTER_EXCHANGE,
            routingKey = routingKey,
            message = Message(original.body, properties),
        )
    }

    private fun invalidCount(): Nothing = throw InvalidReservationConfirmedMessageException("Invalid retry count")

    companion object {
        const val RETRY_COUNT = "x-retry-count"
        const val MAX_ERROR_LENGTH = 120
    }
}

data class ReservationNotificationFailureRoute(val exchange: String, val routingKey: String, val message: Message)
