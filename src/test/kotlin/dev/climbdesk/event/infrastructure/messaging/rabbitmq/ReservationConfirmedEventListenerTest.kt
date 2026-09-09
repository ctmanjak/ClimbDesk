package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.rabbitmq.client.Channel
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventEnvelopeV1
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventPayloadV1
import dev.climbdesk.notification.application.ReservationConfirmedNotificationCommand
import dev.climbdesk.notification.application.ReservationConfirmedNotificationUseCase
import dev.climbdesk.notification.application.ReservationNotificationHandlingResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import java.time.Instant

class ReservationConfirmedEventListenerTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val notificationUseCase = Mockito.mock(ReservationConfirmedNotificationUseCase::class.java)
    private val channel = Mockito.mock(Channel::class.java)
    private val publisher = Mockito.mock(RabbitNotificationFailurePublisher::class.java)
    private val listener = ReservationConfirmedEventListener(
        objectMapper, notificationUseCase, ReservationNotificationFailureClassifier(),
        ReservationNotificationFailureRouter(Clock.systemUTC()), publisher,
    )

    @Test
    fun `listener validates maps handles and acknowledges in that order`() {
        val envelope = validEnvelope()
        val command = expectedCommand(envelope)
        Mockito.`when`(notificationUseCase.handle(command))
            .thenReturn(ReservationNotificationHandlingResult.PROCESSED)

        listener.consume(validMessage(envelope), channel)

        val order = Mockito.inOrder(notificationUseCase, channel)
        order.verify(notificationUseCase).handle(command)
        order.verify(channel).basicAck(DELIVERY_TAG, false)
        Mockito.verifyNoInteractions(publisher)
    }

    @Test
    fun `duplicate no-op is acknowledged after handler returns`() {
        val envelope = validEnvelope()
        val command = expectedCommand(envelope)
        Mockito.`when`(notificationUseCase.handle(command))
            .thenReturn(ReservationNotificationHandlingResult.DUPLICATE)

        listener.consume(validMessage(envelope), channel)

        val order = Mockito.inOrder(notificationUseCase, channel)
        order.verify(notificationUseCase).handle(command)
        order.verify(channel).basicAck(DELIVERY_TAG, false)
        Mockito.verifyNoInteractions(publisher)
    }

    @Test
    fun `handler failure returns before retry republish and confirmed republish precedes ack`() {
        val envelope = validEnvelope()
        val command = expectedCommand(envelope)
        Mockito.`when`(notificationUseCase.handle(command)).thenThrow(IllegalStateException("database failure"))
        listener.consume(validMessage(envelope), channel)
        val order = Mockito.inOrder(notificationUseCase, publisher, channel)
        order.verify(notificationUseCase).handle(command)
        order.verify(publisher).publish(Mockito.any(ReservationNotificationFailureRoute::class.java) ?: placeholderRoute())
        order.verify(channel).basicAck(DELIVERY_TAG, false)
    }

    @ParameterizedTest
    @EnumSource(FailureRepublishReason::class)
    fun `unconfirmed retry or DLQ propagates without ack reject or nack`(reason: FailureRepublishReason) {
        val envelope = validEnvelope()
        Mockito.`when`(notificationUseCase.handle(expectedCommand(envelope))).thenThrow(IllegalStateException("DB failure"))
        Mockito.doThrow(FailureRepublishException(reason)).`when`(publisher)
            .publish(Mockito.any(ReservationNotificationFailureRoute::class.java) ?: placeholderRoute())
        val corrupt = Message("{".toByteArray(), validMessage(envelope).messageProperties)
        listOf(validMessage(envelope), corrupt).forEach {
            assertThatThrownBy { listener.consume(it, channel) }
                .isInstanceOf(FailureRepublishException::class.java).hasMessageContaining(reason.name)
        }
        Mockito.verifyNoInteractions(channel)
    }

    @Test
    fun `invalid envelope and AMQP metadata reach confirmed DLQ before ack without handler`() {
        val envelope = validEnvelope()
        val invalidMessages =
            listOf(
                validMessage(envelope.copy(eventId = 0)),
                validMessage(envelope.copy(eventType = "reservation.unknown")),
                validMessage(envelope.copy(schemaVersion = 2)),
                validMessage(envelope.copy(producer = "unknown")),
                validMessage(envelope.copy(aggregateType = "Unknown")),
                validMessage(envelope.copy(aggregateId = envelope.aggregateId + 1)),
                validMessage(envelope.copy(payload = envelope.payload.copy(reservationId = 0))),
                validMessage(envelope.copy(payload = envelope.payload.copy(memberId = 0))),
                validMessage(envelope.copy(payload = envelope.payload.copy(classSessionId = 0))),
                validMessage(envelope.copy(payload = envelope.payload.copy(memberPassId = 0))),
                validMessage(envelope) { messageId = "999" },
                validMessage(envelope) { type = "reservation.unknown" },
                validMessage(envelope) { setHeader("x-schema-version", 2) },
                validMessage(envelope) { setHeader("x-retry-count", -1) },
                validMessage(envelope) { setHeader("x-retry-count", "0") },
                validMessage(envelope) { setHeader("x-retry-count", Long.MAX_VALUE) },
            )

        invalidMessages.forEach { message ->
            listener.consume(message, channel)
        }
        Mockito.verifyNoInteractions(notificationUseCase)
        val order = Mockito.inOrder(publisher, channel)
        invalidMessages.forEach { _ ->
            val route = org.mockito.ArgumentCaptor.forClass(ReservationNotificationFailureRoute::class.java)
            order.verify(publisher).publish(route.capture() ?: placeholderRoute())
            assertThat(route.value.routingKey).isEqualTo(RabbitMqTopology.DEAD_LETTER_QUEUE)
            order.verify(channel).basicAck(DELIVERY_TAG, false)
        }
    }

    @Test
    fun `corrupt JSON reaches confirmed DLQ without exposing its content`() {
        val message = validMessage(validEnvelope()).let {
            Message("{\"email\":\"sensitive@example.com\"".toByteArray(), it.messageProperties)
        }

        listener.consume(message, channel)
        val route = org.mockito.ArgumentCaptor.forClass(ReservationNotificationFailureRoute::class.java)
        val order = Mockito.inOrder(publisher, channel)
        order.verify(publisher).publish(route.capture() ?: placeholderRoute())
        assertThat(route.value.routingKey).isEqualTo(RabbitMqTopology.DEAD_LETTER_QUEUE)
        assertThat(route.value.message.messageProperties.headers.toString()).doesNotContain("sensitive@example.com")
        order.verify(channel).basicAck(DELIVERY_TAG, false)
        Mockito.verifyNoInteractions(notificationUseCase)
    }

    @Test
    fun `ack failure after handler return propagates without failure routing`() {
        val envelope = validEnvelope()
        Mockito.`when`(notificationUseCase.handle(expectedCommand(envelope)))
            .thenReturn(ReservationNotificationHandlingResult.PROCESSED)
        Mockito.doThrow(java.io.IOException("channel closed")).`when`(channel).basicAck(DELIVERY_TAG, false)
        assertThatThrownBy { listener.consume(validMessage(envelope), channel) }
            .isInstanceOf(java.io.IOException::class.java)
        Mockito.verifyNoInteractions(publisher)
    }

    private fun placeholderRoute() = ReservationNotificationFailureRoute("", "", Message(ByteArray(0)))

    private fun validMessage(
        envelope: ReservationConfirmedEventEnvelopeV1,
        customize: MessageProperties.() -> Unit = {},
    ): Message {
        val properties = MessageProperties().apply {
            messageId = envelope.eventId.toString()
            type = "reservation.confirmed"
            deliveryTag = DELIVERY_TAG
            setHeader("x-schema-version", 1)
            customize()
        }
        return Message(objectMapper.writeValueAsBytes(envelope), properties)
    }

    private fun validEnvelope(): ReservationConfirmedEventEnvelopeV1 =
        ReservationConfirmedEventEnvelopeV1(
            eventId = 101,
            eventType = "reservation.confirmed",
            schemaVersion = 1,
            producer = "climbdesk",
            aggregateType = "Reservation",
            aggregateId = 201,
            occurredAt = Instant.parse("2026-08-16T00:00:00Z"),
            payload =
                ReservationConfirmedEventPayloadV1(
                    reservationId = 201,
                    memberId = 301,
                    classSessionId = 401,
                    memberPassId = 501,
                ),
        )

    private fun expectedCommand(envelope: ReservationConfirmedEventEnvelopeV1) =
        ReservationConfirmedNotificationCommand(
            eventId = envelope.eventId,
            reservationId = envelope.payload.reservationId,
            memberId = envelope.payload.memberId,
            classSessionId = envelope.payload.classSessionId,
            memberPassId = envelope.payload.memberPassId,
        )

    private companion object {
        const val DELIVERY_TAG = 42L
    }
}
