package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import dev.climbdesk.event.application.OutboundMessagePublisher
import dev.climbdesk.event.application.PollingOutboxPublisher
import dev.climbdesk.event.infrastructure.scheduling.OutboxPublishScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Declarables
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.HealthContributorRegistry
import org.springframework.boot.autoconfigure.amqp.RabbitProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "climbdesk.messaging.rabbitmq.enabled=false",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///rabbit-disabled-context",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.rabbitmq.host=broker-must-not-be-required.invalid",
        "spring.rabbitmq.port=1",
    ],
)
class RabbitMqDisabledContextIntegrationTest @Autowired constructor(
    private val applicationContext: ApplicationContext,
    private val rabbitMqProperties: RabbitMqProperties,
    private val rabbitProperties: RabbitProperties,
    private val healthContributorRegistry: HealthContributorRegistry,
) {
    @Test
    fun `application context starts without a broker when RabbitMQ is disabled`() {
        assertThat(rabbitMqProperties.enabled).isFalse()
        assertThat(rabbitMqProperties.publisherEnabled).isFalse()
        assertThat(rabbitMqProperties.listenerEnabled).isFalse()
        assertThat(rabbitMqProperties.publisher.pollInterval).hasSeconds(1)
        assertThat(rabbitMqProperties.publisher.maxPerTick).isEqualTo(20)
        assertThat(rabbitMqProperties.publisher.maxAttempts).isEqualTo(5)
        assertThat(rabbitMqProperties.publisher.confirmTimeout).hasSeconds(5)
        assertThat(rabbitMqProperties.publisher.retryBackoffs)
            .containsExactly(
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofMinutes(2),
                java.time.Duration.ofMinutes(10),
            )
        assertThat(rabbitProperties.username).isEqualTo("climbdesk")
        assertThat(rabbitProperties.password).isEqualTo("climbdesk")
        assertThat(applicationContext.getBeansOfType(Declarables::class.java)).isEmpty()
        assertThat(applicationContext.getBeansOfType(PollingOutboxPublisher::class.java)).isEmpty()
        assertThat(applicationContext.getBeansOfType(OutboundMessagePublisher::class.java)).isEmpty()
        assertThat(applicationContext.getBeansOfType(OutboxPublishScheduler::class.java)).isEmpty()
        assertThat(healthContributorRegistry.getContributor("rabbit")).isNull()
    }
}
