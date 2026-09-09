package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "climbdesk.messaging.rabbitmq")
data class RabbitMqProperties(
    val enabled: Boolean = false,
    val publisherEnabled: Boolean = false,
    val listenerEnabled: Boolean = false,
    val publisher: Publisher = Publisher(),
    val observability: Observability = Observability(),
) {
    data class Publisher(
        val pollInterval: Duration = Duration.ofSeconds(1),
        val maxPerTick: Int = 20,
        val maxAttempts: Int = 5,
        val confirmTimeout: Duration = Duration.ofSeconds(5),
        val retryBackoffs: List<Duration> =
            listOf(
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(10),
            ),
    )

    data class Observability(
        val managementBaseUrl: String = "http://localhost:15672",
        val sampleInterval: Duration = Duration.ofSeconds(5),
        val requestTimeout: Duration = Duration.ofSeconds(2),
    )
}
