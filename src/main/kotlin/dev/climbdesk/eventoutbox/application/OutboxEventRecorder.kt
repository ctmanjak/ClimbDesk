package dev.climbdesk.eventoutbox.application

import dev.climbdesk.eventoutbox.domain.OutboxEvent
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent

interface OutboxEventRecorder {
    fun record(event: ReservationConfirmedEvent): OutboxEvent
}
