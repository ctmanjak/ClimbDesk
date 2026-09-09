package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.ReturnedMessage
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class RabbitNotificationFailurePublisherTest {
    private val route = ReservationNotificationFailureRoute("exchange", "key", Message("{}".toByteArray()))

    @Test
    fun `confirm ACK without return succeeds`() {
        val publisher = RabbitNotificationFailurePublisher(template { it.future.complete(CorrelationData.Confirm(true, null)) }, Duration.ofSeconds(1))
        assertThatCode { publisher.publish(route) }.doesNotThrowAnyException()
    }

    @ParameterizedTest
    @EnumSource(FailureRepublishReason::class)
    fun `all unconfirmed outcomes fail safely and interrupt flag is restored`(reason: FailureRepublishReason) {
        val template = template {
            when (reason) {
                FailureRepublishReason.NACK -> it.future.complete(CorrelationData.Confirm(false, "sensitive@example.com"))
                FailureRepublishReason.TIMEOUT -> Unit
                FailureRepublishReason.MANDATORY_RETURN -> {
                    it.returned = ReturnedMessage(route.message, 312, "sensitive@example.com", "exchange", "key")
                    it.future.complete(CorrelationData.Confirm(true, null))
                }
                FailureRepublishReason.INTERRUPTED -> Thread.currentThread().interrupt()
                FailureRepublishReason.SEND_OR_CONNECTION -> throw IllegalStateException("sensitive@example.com")
            }
        }
        try {
            assertThatThrownBy { RabbitNotificationFailurePublisher(template, Duration.ofMillis(20)).publish(route) }
                .isInstanceOfSatisfying(FailureRepublishException::class.java) {
                    assertThat(it.reason).isEqualTo(reason)
                    assertThat(it.cause).isNull()
                    assertThat(it.message).doesNotContain("sensitive")
                }
            assertThat(Thread.currentThread().isInterrupted).isEqualTo(reason == FailureRepublishReason.INTERRUPTED)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `mandatory return takes precedence over NACK`() {
        val template = template {
            it.returned = ReturnedMessage(route.message, 312, "returned", "exchange", "key")
            it.future.complete(CorrelationData.Confirm(false, "nack"))
        }
        assertThatThrownBy { RabbitNotificationFailurePublisher(template, Duration.ofSeconds(1)).publish(route) }
            .isInstanceOfSatisfying(FailureRepublishException::class.java) {
                assertThat(it.reason).isEqualTo(FailureRepublishReason.MANDATORY_RETURN)
            }
    }

    @Test
    fun `exceptional confirm completion is a connection failure`() {
        val template = template { it.future.completeExceptionally(IllegalStateException("connection closed")) }
        assertThatThrownBy { RabbitNotificationFailurePublisher(template, Duration.ofSeconds(1)).publish(route) }
            .hasMessageContaining("SEND_OR_CONNECTION")
    }

    @Test
    fun `each republish has separate correlation even with the same message id`() {
        val last = AtomicReference<CorrelationData>()
        val template = template { last.set(it); it.future.complete(CorrelationData.Confirm(true, null)) }
        val publisher = RabbitNotificationFailurePublisher(template, Duration.ofSeconds(1))
        publisher.publish(route)
        val first = last.get()
        publisher.publish(route)
        assertThat(last.get().id).isNotEqualTo(first.id)
    }

    @Test
    fun `publisher requires correlated confirms returns mandatory and a positive timeout`() {
        val factory = CachingConnectionFactory("localhost")
        val template = RabbitTemplate(factory)
        assertThatThrownBy { RabbitNotificationFailurePublisher(template, Duration.ofSeconds(1)) }.hasMessageContaining("correlated")
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED)
        assertThatThrownBy { RabbitNotificationFailurePublisher(template, Duration.ofSeconds(1)) }.hasMessageContaining("returns")
        factory.isPublisherReturns = true
        assertThatThrownBy { RabbitNotificationFailurePublisher(template, Duration.ofSeconds(1)) }.hasMessageContaining("mandatory")
        template.setMandatory(true)
        assertThatThrownBy { RabbitNotificationFailurePublisher(template, Duration.ZERO) }.hasMessageContaining("positive")
    }

    private fun template(onSend: (CorrelationData) -> Unit): RabbitTemplate {
        val factory = CachingConnectionFactory("localhost").apply {
            setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED)
            isPublisherReturns = true
        }
        val template = Mockito.spy(RabbitTemplate(factory))
        template.setMandatory(true)
        Mockito.doAnswer { onSend(it.getArgument(3)); null }.`when`(template).send(
            Mockito.eq(route.exchange), Mockito.eq(route.routingKey),
            Mockito.any(Message::class.java) ?: route.message,
            Mockito.any(CorrelationData::class.java) ?: CorrelationData(),
        )
        return template
    }
}
