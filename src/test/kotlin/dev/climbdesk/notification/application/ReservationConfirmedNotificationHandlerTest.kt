package dev.climbdesk.notification.application

import dev.climbdesk.reservation.domain.ReservationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReservationConfirmedNotificationHandlerTest {
    private val processedEventStore = Mockito.mock(ProcessedEventStore::class.java)
    private val notificationRequestStore = Mockito.mock(ReservationNotificationRequestStore::class.java)
    private val reservationRepository = Mockito.mock(ReservationRepository::class.java)
    private val processedAt = Instant.parse("2026-08-16T00:00:00Z")
    private val handler =
        ReservationConfirmedNotificationHandler(
            processedEventStore = processedEventStore,
            notificationRequestStore = notificationRequestStore,
            reservationRepository = reservationRepository,
            clock = Clock.fixed(processedAt, ZoneOffset.UTC),
        )

    @Test
    fun `processed conflict returns duplicate without reading reservation or creating a result`() {
        val command =
            ReservationConfirmedNotificationCommand(
                eventId = 101,
                reservationId = 201,
                memberId = 301,
                classSessionId = 401,
                memberPassId = 501,
            )
        Mockito.`when`(
            processedEventStore.insertIfAbsent(
                ReservationConfirmedNotificationHandler.CONSUMER_NAME,
                command.eventId,
                ReservationConfirmedNotificationHandler.EVENT_TYPE,
                processedAt,
            ),
        ).thenReturn(false)

        assertThat(handler.handle(command)).isEqualTo(ReservationNotificationHandlingResult.DUPLICATE)

        Mockito.verifyNoInteractions(reservationRepository, notificationRequestStore)
    }
}
