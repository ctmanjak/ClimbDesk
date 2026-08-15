package dev.climbdesk.event.infrastructure.messaging.rabbitmq

object RabbitMqTopology {
    const val MAIN_EXCHANGE = "climbdesk.events"
    const val MAIN_ROUTING_KEY = "reservation.confirmed.v1"
    const val MAIN_QUEUE = "climbdesk.reservation-notification.v1"

    const val RETRY_EXCHANGE = "climbdesk.events.retry"
    const val RETRY_QUEUE_5S = "$MAIN_QUEUE.retry.5s"
    const val RETRY_QUEUE_30S = "$MAIN_QUEUE.retry.30s"
    const val RETRY_QUEUE_2M = "$MAIN_QUEUE.retry.2m"
    const val RETRY_TTL_5S = 5_000
    const val RETRY_TTL_30S = 30_000
    const val RETRY_TTL_2M = 120_000

    const val DEAD_LETTER_EXCHANGE = "climbdesk.events.dlx"
    const val DEAD_LETTER_QUEUE = "$MAIN_QUEUE.dlq"

    val retryQueues =
        listOf(
            RetryQueue(RETRY_QUEUE_5S, RETRY_TTL_5S),
            RetryQueue(RETRY_QUEUE_30S, RETRY_TTL_30S),
            RetryQueue(RETRY_QUEUE_2M, RETRY_TTL_2M),
        )

    data class RetryQueue(
        val name: String,
        val ttl: Int,
    )
}
