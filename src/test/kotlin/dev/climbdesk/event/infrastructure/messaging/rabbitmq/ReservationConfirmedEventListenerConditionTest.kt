package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.notification.application.ReservationConfirmedNotificationUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

class ReservationConfirmedEventListenerConditionTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(ListenerTestConfiguration::class.java)

    @Test
    fun `RabbitMQ disabled does not create the listener`() {
        contextRunner
            .withPropertyValues(
                "climbdesk.messaging.rabbitmq.enabled=false",
                "climbdesk.messaging.rabbitmq.listener-enabled=true",
            )
            .run { context ->
                assertThat(context).doesNotHaveBean(ReservationConfirmedEventListener::class.java)
            }
    }

    @Test
    fun `listener disabled does not create the listener`() {
        contextRunner
            .withPropertyValues(
                "climbdesk.messaging.rabbitmq.enabled=true",
                "climbdesk.messaging.rabbitmq.listener-enabled=false",
            )
            .run { context ->
                assertThat(context).doesNotHaveBean(ReservationConfirmedEventListener::class.java)
            }
    }

    @Test
    fun `listener enabled creates the listener independently of the publisher`() {
        contextRunner
            .withPropertyValues(
                "climbdesk.messaging.rabbitmq.enabled=true",
                "climbdesk.messaging.rabbitmq.listener-enabled=true",
                "climbdesk.messaging.rabbitmq.publisher-enabled=false",
            )
            .run { context ->
                assertThat(context).hasSingleBean(ReservationConfirmedEventListener::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ReservationConfirmedEventListener::class)
    class ListenerTestConfiguration {
        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()

        @Bean
        fun notificationUseCase(): ReservationConfirmedNotificationUseCase =
            Mockito.mock(ReservationConfirmedNotificationUseCase::class.java)
    }
}
