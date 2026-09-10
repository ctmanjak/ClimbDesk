package dev.climbdesk.event.infrastructure.observability

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.event.infrastructure.messaging.rabbitmq.RabbitMqProperties
import org.springframework.boot.autoconfigure.amqp.RabbitProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class MessagingObservabilityConfiguration {
    @Bean
    fun messagingObservation(
        jdbcTemplate: JdbcTemplate,
        properties: RabbitMqProperties,
    ): MessagingMetrics = MessagingMetrics(jdbcTemplate, properties.publisher.maxAttempts)
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "climbdesk.messaging.rabbitmq", name = ["enabled"], havingValue = "true")
@EnableScheduling
class RabbitMqQueueObservabilityConfiguration {
    @Bean(name = [RABBIT_MQ_QUEUE_METRICS_SCHEDULER_BEAN_NAME])
    fun rabbitMqQueueMetricsTaskScheduler() = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("rabbitmq-queue-metrics-")
    }

    @Bean
    fun rabbitMqQueueMetrics(
        properties: RabbitMqProperties,
        rabbitProperties: RabbitProperties,
        objectMapper: ObjectMapper,
    ) = RabbitMqQueueMetrics(
        managementBaseUrl = properties.observability.managementBaseUrl,
        username = rabbitProperties.username,
        password = rabbitProperties.password,
        virtualHost = rabbitProperties.virtualHost ?: "/",
        objectMapper = objectMapper,
        requestTimeout = properties.observability.requestTimeout,
        clock = Clock.systemUTC(),
    )
}
