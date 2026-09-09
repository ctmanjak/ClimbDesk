package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.Channel
import dev.climbdesk.event.infrastructure.messaging.ReservationConfirmedEventEnvelopeV1
import dev.climbdesk.event.application.MessagingObservation
import dev.climbdesk.event.application.NoOpMessagingObservation
import dev.climbdesk.notification.application.ReservationConfirmedNotificationCommand
import dev.climbdesk.notification.application.ReservationNotificationHandlingResult
import dev.climbdesk.notification.application.ReservationConfirmedNotificationUseCase
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory

@Component
@ConditionalOnProperty(
    prefix = "climbdesk.messaging.rabbitmq",
    name = ["enabled", "listener-enabled"],
    havingValue = "true",
)
class ReservationConfirmedEventListener(
    private val objectMapper: ObjectMapper,
    private val notificationUseCase: ReservationConfirmedNotificationUseCase,
    private val failureClassifier: ReservationNotificationFailureClassifier,
    private val failureRouter: ReservationNotificationFailureRouter,
    private val failurePublisher: RabbitNotificationFailurePublisher,
    private val observation: MessagingObservation = NoOpMessagingObservation,
) {
    @RabbitListener(
        id = LISTENER_ID,
        queues = [RabbitMqTopology.MAIN_QUEUE],
        containerFactory = "reservationNotificationListenerContainerFactory",
    )
    fun consume(
        message: Message,
        channel: Channel,
    ) {
        try {
            failureRouter.retryCount(message)
            val envelope = deserialize(message.body)
            validateContract(message, envelope)

            val result = notificationUseCase.handle(
                ReservationConfirmedNotificationCommand(
                    eventId = envelope.eventId,
                    reservationId = envelope.payload.reservationId,
                    memberId = envelope.payload.memberId,
                    classSessionId = envelope.payload.classSessionId,
                    memberPassId = envelope.payload.memberPassId,
                ),
            )
            when (result) {
                ReservationNotificationHandlingResult.PROCESSED -> observation.consumerProcessed(envelope.occurredAt)
                ReservationNotificationHandlingResult.DUPLICATE -> observation.consumerDuplicate()
            }
            logger.info("Reservation notification delivery committed: eventId={} result={}", envelope.eventId, result)
        } catch (exception: Exception) {
            val category = failureClassifier.classify(exception)
            failurePublisher.publish(failureRouter.route(message, category, exception))
        }
        channel.basicAck(message.messageProperties.deliveryTag, false)
    }

    private fun deserialize(body: ByteArray): ReservationConfirmedEventEnvelopeV1 =
        try {
            objectMapper.readValue(body, ReservationConfirmedEventEnvelopeV1::class.java)
        } catch (exception: JsonProcessingException) {
            throw InvalidReservationConfirmedMessageException("Invalid reservation confirmed event JSON")
        }

    private fun validateContract(
        message: Message,
        envelope: ReservationConfirmedEventEnvelopeV1,
    ) {
        val properties = message.messageProperties
        val payload = envelope.payload
        val validSchemaHeader = when (val header = properties.headers[SCHEMA_VERSION_HEADER]) {
            is Byte, is Short, is Int, is Long -> header.toLong() == SCHEMA_VERSION.toLong()
            else -> false
        }
        val valid =
            envelope.eventId > 0 &&
                envelope.eventType == EVENT_TYPE &&
                envelope.schemaVersion == SCHEMA_VERSION &&
                envelope.producer == PRODUCER &&
                envelope.aggregateType == AGGREGATE_TYPE &&
                envelope.aggregateId == payload.reservationId &&
                properties.messageId == envelope.eventId.toString() &&
                properties.type == EVENT_TYPE &&
                validSchemaHeader &&
                payload.reservationId > 0 &&
                payload.memberId > 0 &&
                payload.classSessionId > 0 &&
                payload.memberPassId > 0

        if (!valid) {
            throw InvalidReservationConfirmedMessageException("Invalid reservation confirmed event contract")
        }
    }

    companion object {
        const val LISTENER_ID = "reservation-notification-v1-listener"
        private const val EVENT_TYPE = "reservation.confirmed"
        private const val PRODUCER = "climbdesk"
        private const val AGGREGATE_TYPE = "Reservation"
        private const val SCHEMA_VERSION = 1
        private const val SCHEMA_VERSION_HEADER = "x-schema-version"
        private val logger = LoggerFactory.getLogger(ReservationConfirmedEventListener::class.java)
    }
}

class InvalidReservationConfirmedMessageException(message: String) : RuntimeException(message)
