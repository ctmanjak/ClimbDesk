package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.event.application.OutboundMessagePublisher
import dev.climbdesk.event.application.OutboxEventStore
import dev.climbdesk.event.application.OutboxMessageMapper
import dev.climbdesk.event.application.OutboxPublisherPolicy
import dev.climbdesk.event.application.OutboxPublishUseCase
import dev.climbdesk.event.application.PollingOutboxPublisher
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventEnvelopeMapper
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedOutboxMessageMapper
import dev.climbdesk.event.infrastructure.persistence.OutboxEventJpaRepository
import dev.climbdesk.event.infrastructure.persistence.OutboxEventStoreAdapter
import dev.climbdesk.event.infrastructure.scheduling.OutboxPublishScheduler
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "climbdesk.messaging.rabbitmq",
    name = ["enabled", "publisher-enabled"],
    havingValue = "true",
)
class OutboxPublisherConfiguration {
    @Bean
    fun outboxPublisherPolicy(properties: RabbitMqProperties): OutboxPublisherPolicy =
        OutboxPublisherPolicy(
            pollInterval = properties.publisher.pollInterval,
            maxPerTick = properties.publisher.maxPerTick,
            maxAttempts = properties.publisher.maxAttempts,
            confirmTimeout = properties.publisher.confirmTimeout,
            retryBackoffs = properties.publisher.retryBackoffs,
        )

    @Bean
    fun outboxEventStore(repository: OutboxEventJpaRepository): OutboxEventStore =
        OutboxEventStoreAdapter(repository)

    @Bean
    fun outboxMessageMapper(
        envelopeMapper: ReservationConfirmedEventEnvelopeMapper,
        objectMapper: ObjectMapper,
    ): OutboxMessageMapper = ReservationConfirmedOutboxMessageMapper(envelopeMapper, objectMapper)

    @Bean
    fun outboundMessagePublisher(rabbitTemplate: RabbitTemplate): OutboundMessagePublisher =
        RabbitOutboundMessagePublisher(rabbitTemplate)

    @Bean
    fun pollingOutboxPublisher(
        outboxEventStore: OutboxEventStore,
        outboxMessageMapper: OutboxMessageMapper,
        outboundMessagePublisher: OutboundMessagePublisher,
        policy: OutboxPublisherPolicy,
    ): PollingOutboxPublisher =
        PollingOutboxPublisher(
            outboxEventStore = outboxEventStore,
            outboxMessageMapper = outboxMessageMapper,
            outboundMessagePublisher = outboundMessagePublisher,
            policy = policy,
        )
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "climbdesk.messaging.rabbitmq",
    name = ["enabled", "publisher-enabled"],
    havingValue = "true",
)
class OutboxPublisherSchedulingConfiguration {
    @Bean
    fun outboxPublishScheduler(
        pollingOutboxPublisher: OutboxPublishUseCase,
        policy: OutboxPublisherPolicy,
    ): OutboxPublishScheduler = OutboxPublishScheduler(pollingOutboxPublisher, policy)
}
