package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class RabbitMqPropertiesTest {
    @Test
    fun `publisher settings have code defaults`() {
        val properties = RabbitMqProperties()

        assertThat(properties.publisher.pollInterval).isEqualTo(Duration.ofSeconds(1))
        assertThat(properties.publisher.maxPerTick).isEqualTo(20)
        assertThat(properties.publisher.maxAttempts).isEqualTo(5)
        assertThat(properties.publisher.confirmTimeout).isEqualTo(Duration.ofSeconds(5))
        assertThat(properties.publisher.retryBackoffs)
            .containsExactly(
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(10),
            )
    }
}
