package dev.climbdesk.notification.application

import dev.climbdesk.notification.domain.ReservationNotificationRequest

interface ReservationNotificationRequestStore {
    fun save(request: ReservationNotificationRequest)
}
