package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import dev.climbdesk.event.application.OutboundMessage
import dev.climbdesk.event.application.PublishResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class RabbitOutboundMessagePublisherTest {
    @Test
    fun `publisher requires a caching connection factory`() {
        val rabbitTemplate = RabbitTemplate(mock(ConnectionFactory::class.java))
        rabbitTemplate.setMandatory(true)

        assertThatThrownBy { RabbitOutboundMessagePublisher(rabbitTemplate) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires CachingConnectionFactory")
    }

    @Test
    fun `publisher requires correlated confirms`() {
        val rabbitTemplate = RabbitTemplate(CachingConnectionFactory("localhost"))
        rabbitTemplate.setMandatory(true)

        assertThatThrownBy { RabbitOutboundMessagePublisher(rabbitTemplate) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires correlated confirms")
            .hasMessageContaining("actual publisherConfirms=false")
    }

    @Test
    fun `publisher requires mandatory publishing`() {
        val rabbitTemplate = RabbitTemplate(correlatedConnectionFactory())

        assertThatThrownBy { RabbitOutboundMessagePublisher(rabbitTemplate) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires mandatory publishing")
            .hasMessageContaining("actual mandatory=false")
    }

    @Test
    fun `publisher accepts correlated confirms and mandatory publishing`() {
        val rabbitTemplate = RabbitTemplate(correlatedConnectionFactory())
        rabbitTemplate.setMandatory(true)

        assertThatCode { RabbitOutboundMessagePublisher(rabbitTemplate) }.doesNotThrowAnyException()
    }

    @Test
    fun `publisher returns failure when the captured correlated confirm is NACK`() {
        val capturedCorrelation = AtomicReference<CorrelationData>()
        val rabbitTemplate = controlledRabbitTemplate { correlationData ->
            capturedCorrelation.set(correlationData)
            correlationData.future.complete(CorrelationData.Confirm(false, "broker rejected publish"))
        }

        val result = RabbitOutboundMessagePublisher(rabbitTemplate).publish(message(), Duration.ofSeconds(1))

        assertThat(capturedCorrelation.get().id).isEqualTo("event-101")
        assertThat(result).isInstanceOfSatisfying(PublishResult.Failure::class.java) { failure ->
            assertThat(failure.reason)
                .contains("publisher confirm NACK")
                .contains("broker rejected publish")
        }
        assertThat(result).isNotEqualTo(PublishResult.Success)
    }

    @Test
    fun `publisher returns failure when the captured correlated confirm times out`() {
        val capturedCorrelation = AtomicReference<CorrelationData>()
        val rabbitTemplate = controlledRabbitTemplate(capturedCorrelation::set)

        val result = assertTimeoutPreemptively<PublishResult>(Duration.ofSeconds(2)) {
            RabbitOutboundMessagePublisher(rabbitTemplate).publish(message(), Duration.ofMillis(250))
        }

        assertThat(capturedCorrelation.get().id).isEqualTo("event-101")
        assertThat(capturedCorrelation.get().future).isNotDone()
        assertThat(result).isInstanceOfSatisfying(PublishResult.Failure::class.java) { failure ->
            assertThat(failure.reason).contains("publisher confirm timeout after 250ms")
        }
        assertThat(result).isNotEqualTo(PublishResult.Success)
    }

    private fun controlledRabbitTemplate(onSend: (CorrelationData) -> Unit): RabbitTemplate {
        val rabbitTemplate = Mockito.spy(RabbitTemplate(correlatedConnectionFactory()))
        rabbitTemplate.setMandatory(true)
        Mockito.doAnswer { invocation ->
            onSend(invocation.getArgument(3))
            null
        }.`when`(rabbitTemplate).send(
            Mockito.eq(RabbitMqTopology.MAIN_EXCHANGE),
            Mockito.eq(RabbitMqTopology.MAIN_ROUTING_KEY),
            Mockito.any(Message::class.java) ?: Message(ByteArray(0)),
            Mockito.any(CorrelationData::class.java) ?: CorrelationData(),
        )
        return rabbitTemplate
    }

    private fun message() =
        OutboundMessage(
            messageId = "event-101",
            eventType = "reservation.confirmed",
            schemaVersion = 1,
            producer = "climbdesk",
            body = "{}".toByteArray(),
        )

    private fun correlatedConnectionFactory() =
        CachingConnectionFactory("localhost").apply {
            setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED)
        }
}
