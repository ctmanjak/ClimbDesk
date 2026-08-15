package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarable
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "climbdesk.messaging.rabbitmq",
    name = ["enabled"],
    havingValue = "true",
)
class RabbitMqTopologyConfiguration {
    @Bean
    fun reservationNotificationTopology(): Declarables {
        val mainExchange = TopicExchange(RabbitMqTopology.MAIN_EXCHANGE, true, false)
        val retryExchange = DirectExchange(RabbitMqTopology.RETRY_EXCHANGE, true, false)
        val deadLetterExchange = DirectExchange(RabbitMqTopology.DEAD_LETTER_EXCHANGE, true, false)
        val mainQueue =
            QueueBuilder.durable(RabbitMqTopology.MAIN_QUEUE)
                .deadLetterExchange(RabbitMqTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RabbitMqTopology.DEAD_LETTER_QUEUE)
                .build()
        val deadLetterQueue = QueueBuilder.durable(RabbitMqTopology.DEAD_LETTER_QUEUE).build()
        val declarables =
            mutableListOf<Declarable>(
                mainExchange,
                retryExchange,
                deadLetterExchange,
                mainQueue,
                deadLetterQueue,
                BindingBuilder.bind(mainQueue)
                    .to(mainExchange)
                    .with(RabbitMqTopology.MAIN_ROUTING_KEY),
                BindingBuilder.bind(deadLetterQueue)
                    .to(deadLetterExchange)
                    .with(RabbitMqTopology.DEAD_LETTER_QUEUE),
            )

        RabbitMqTopology.retryQueues.forEach { retry ->
            val queue = retryQueue(retry)
            declarables += queue
            declarables += BindingBuilder.bind(queue).to(retryExchange).with(retry.name)
        }

        return Declarables(*declarables.toTypedArray())
    }

    private fun retryQueue(retry: RabbitMqTopology.RetryQueue): Queue =
        QueueBuilder.durable(retry.name)
            .ttl(retry.ttl)
            .deadLetterExchange(RabbitMqTopology.MAIN_EXCHANGE)
            .deadLetterRoutingKey(RabbitMqTopology.MAIN_ROUTING_KEY)
            .build()
}
