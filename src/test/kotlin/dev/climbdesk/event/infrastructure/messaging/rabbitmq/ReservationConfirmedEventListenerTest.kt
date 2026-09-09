package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.rabbitmq.client.Channel
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventEnvelopeV1
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventPayloadV1
import dev.climbdesk.notification.application.ReservationConfirmedNotificationCommand
import dev.climbdesk.notification.application.ReservationConfirmedNotificationUseCase
import dev.climbdesk.notification.application.ReservationNotificationHandlingResult
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import java.time.Instant

class ReservationConfirmedEventListenerTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val notificationUseCase = Mockito.mock(ReservationConfirmedNotificationUseCase::class.java)
    private val channel = Mockito.mock(Channel::class.java)
    private val listener = ReservationConfirmedEventListener(objectMapper, notificationUseCase)

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
    }

    @Test
    fun `handler failure propagates without acknowledgement`() {
        val envelope = validEnvelope()
        Mockito.`when`(notificationUseCase.handle(expectedCommand(envelope)))
            .thenThrow(IllegalStateException("database failure"))

        assertThatThrownBy { listener.consume(validMessage(envelope), channel) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("database failure")

        Mockito.verify(channel, Mockito.never()).basicAck(Mockito.anyLong(), Mockito.anyBoolean())
    }

    @Test
    fun `invalid envelope and AMQP metadata do not reach the handler or acknowledge`() {
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
            )

        invalidMessages.forEach { message ->
            assertThatThrownBy { listener.consume(message, channel) }
                .isInstanceOf(InvalidReservationConfirmedMessageException::class.java)
        }
        Mockito.verifyNoInteractions(notificationUseCase, channel)
    }

    @Test
    fun `corrupt JSON is rejected without exposing its content or acknowledging`() {
        val message = validMessage(validEnvelope()).let {
            Message("{\"email\":\"sensitive@example.com\"".toByteArray(), it.messageProperties)
        }

        assertThatThrownBy { listener.consume(message, channel) }
            .isInstanceOf(InvalidReservationConfirmedMessageException::class.java)
            .hasMessage("Invalid reservation confirmed event JSON")
            .hasMessageNotContaining("sensitive@example.com")
        Mockito.verifyNoInteractions(notificationUseCase, channel)
    }

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
