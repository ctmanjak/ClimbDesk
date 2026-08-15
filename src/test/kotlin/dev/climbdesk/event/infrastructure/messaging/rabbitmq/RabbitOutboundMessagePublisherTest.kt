package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.mockito.Mockito.mock

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

    private fun correlatedConnectionFactory() =
        CachingConnectionFactory("localhost").apply {
            setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED)
        }
}
