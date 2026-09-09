package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "climbdesk.messaging.rabbitmq",
    name = ["enabled", "listener-enabled"],
    havingValue = "true",
)
class ReservationNotificationConsumerConfiguration {
    @Bean
    fun reservationNotificationFailureClassifier() = ReservationNotificationFailureClassifier()

    @Bean
    fun reservationNotificationFailureRouter() = ReservationNotificationFailureRouter(Clock.systemUTC())

    @Bean
    fun rabbitNotificationFailurePublisher(template: RabbitTemplate, properties: RabbitMqProperties) =
        RabbitNotificationFailurePublisher(template, properties.publisher.confirmTimeout)

    @Bean
    fun reservationNotificationListenerContainerFactory(
        configurer: SimpleRabbitListenerContainerFactoryConfigurer,
        connectionFactory: ConnectionFactory,
    ): SimpleRabbitListenerContainerFactory = SimpleRabbitListenerContainerFactory().apply {
        configurer.configure(this, connectionFactory)
        // In MANUAL mode an escaped failure stays unacked until channel/connection recovery.
        // Avoid the default error handler's payload/stack trace logs and fatal rejection path.
        setErrorHandler { exception ->
            logger.warn(
                "Reservation notification delivery remains unacknowledged: exceptionClass={}",
                exception.javaClass.simpleName,
            )
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ReservationNotificationConsumerConfiguration::class.java)
    }
}
