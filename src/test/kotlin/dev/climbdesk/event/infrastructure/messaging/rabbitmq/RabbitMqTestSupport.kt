package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.awaitility.Awaitility.await
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.time.Duration

internal fun RabbitTemplate.awaitAmqpReady() {
    await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until {
        runCatching { execute { channel -> channel.isOpen } == true }.getOrDefault(false)
    }
}
