package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "climbdesk.messaging.rabbitmq")
data class RabbitMqProperties(
    val enabled: Boolean = false,
    val publisherEnabled: Boolean = false,
    val listenerEnabled: Boolean = false,
)
