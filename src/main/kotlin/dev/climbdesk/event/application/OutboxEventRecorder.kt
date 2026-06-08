package dev.climbdesk.event.application

import dev.climbdesk.event.domain.OutboxEvent
import dev.climbdesk.reservation.domain.ReservationCanceledEvent
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent

interface OutboxEventRecorder {
    fun record(event: ReservationConfirmedEvent): OutboxEvent
    fun record(event: ReservationCanceledEvent): OutboxEvent
}
