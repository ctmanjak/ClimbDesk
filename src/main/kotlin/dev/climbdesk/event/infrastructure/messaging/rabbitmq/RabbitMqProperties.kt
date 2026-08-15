package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "climbdesk.messaging.rabbitmq")
data class RabbitMqProperties(
    val enabled: Boolean = false,
    val publisherEnabled: Boolean = false,
    val listenerEnabled: Boolean = false,
    val publisher: Publisher,
) {
    data class Publisher(
        val pollInterval: Duration,
        val maxPerTick: Int,
        val maxAttempts: Int,
        val confirmTimeout: Duration,
        val retryBackoffs: List<Duration>,
    )
}
